package com.example.knowledge.controller;

import com.example.knowledge.common.ApiResponse;
import com.example.knowledge.conf.RagProperties;
import com.example.knowledge.rag.HybridSearchService;
import com.example.knowledge.rag.KnowledgeBaseLoader;
import com.example.knowledge.rag.KeywordDocumentSearchService;
import com.example.knowledge.rag.VectorStoreMigrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 知识库管理接口（受 AdminAuthInterceptor 保护，路径前缀 /rag/admin）。
 */
@Slf4j
@RestController
@RequestMapping("/rag/admin/knowledge")
@RequiredArgsConstructor
public class KnowledgeAdminController {

    private final KnowledgeBaseLoader loader;
    private final VectorStore vectorStore;
    private final VectorStoreMigrationService migrationService;
    private final HybridSearchService hybridSearchService;
    private final KeywordDocumentSearchService keywordSearchService;
    private final RagProperties props;

    @Value("${spring.ai.vectorstore.type:pgvector}")
    private String vectorStoreType;

    /**
     * 加载/重载知识库。
     *
     * @param force false=增量（只处理库中尚不存在的来源）；true=全量重载
     * @return 本次入库的片段数与是否走了强制重载
     */
    @PostMapping("/load")
    public ApiResponse<Map<String, Object>> load(@RequestParam(defaultValue = "false") boolean force) {
        int chunks = loader.loadAll(force);
        return ApiResponse.ok(Map.of("loadedChunks", chunks, "forced", force));
    }

    /**
     * 检索预览：查看某问题实际召回哪些知识片段，便于调参。
     *
     * @param q    检索词
     * @param topK 返回条数；缺省用 {@code app.rag.top-k}
     * @param mode vector=仅向量路 / keyword=仅关键词路 / hybrid=双路 RRF 融合（默认）
     * @return 召回片段的分数、来源与摘要
     */
    @GetMapping("/search")
    public ApiResponse<List<Map<String, Object>>> search(@RequestParam String q,
                                                         @RequestParam(required = false) Integer topK,
                                                         @RequestParam(defaultValue = "hybrid") String mode) {
        int limit = topK == null ? props.getTopK() : topK;
        List<Document> docs = switch (mode.toLowerCase(Locale.ROOT)) {
            case "vector" -> new ArrayList<>(hybridSearchService.searchByVector(q, limit));
            case "keyword" -> new ArrayList<>(keywordSearchService.search(q, limit));
            default -> new ArrayList<>(hybridSearchService.search(q, limit).documents());
        };
        return ApiResponse.ok(docs.stream().map(this::toView).toList());
    }

    /**
     * 混合检索调试：返回双路各自的召回情况与 RRF 融合明细，用于排查"为什么召不回来"。
     * GET /rag/admin/knowledge/search/hybrid?q=你的问题
     *
     * @param q 检索词
     * @return 两路命中数、是否降级、以及融合后的得分与名次明细
     */
    @GetMapping("/search/hybrid")
    public ApiResponse<Map<String, Object>> hybridSearch(@RequestParam String q) {
        HybridSearchService.HybridResult result = hybridSearchService.search(q);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", q);
        body.put("hybridEnabled", props.getHybrid().isEnabled());
        body.put("keywordAvailable", result.keywordAvailable());
        body.put("degraded", result.degraded());
        body.put("vectorCount", result.vectorDocs().size());
        body.put("keywordCount", result.keywordDocs().size());
        body.put("finalCount", result.documents().size());
        body.put("vectorHits", result.vectorDocs().stream().map(this::toView).toList());
        body.put("keywordHits", result.keywordDocs().stream().map(this::toView).toList());
        body.put("fused", result.trace());
        return ApiResponse.ok(body);
    }

    /**
     * 上传文档并入库。支持 txt / md / pdf / doc / docx / xlsx / pptx。
     * 文件保存在 app.rag.doc-dir 目录，后续会被自动发现，无需改配置或重启。
     *
     * @param file 上传的文件
     * @return 文件名与入库片段数
     * @throws IllegalArgumentException 文件为空、无文件名或解析不出文本时抛出
     */
    @PostMapping("/upload")
    public ApiResponse<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        if (file == null) {
            throw new IllegalArgumentException("未接收到上传文件，请重新选择文件后重试");
        }
        String filename = StringUtils.cleanPath(
                Optional.ofNullable(file.getOriginalFilename()).orElse(""));
        log.info("收到上传请求: 文件名={}, 大小={} bytes, 内容类型={}",
                filename, file.getSize(), file.getContentType());

        if (file.isEmpty()) {
            throw new IllegalArgumentException(
                    "文件「" + (filename.isBlank() ? "未命名" : filename) + "」为 0 字节，请确认文件内容不为空");
        }
        if (filename.isBlank()) {
            throw new IllegalArgumentException("无法获取文件名，请重命名文件后重试");
        }

        try {
            int chunks = loader.saveAndLoad(filename, file.getBytes());
            if (chunks == 0) {
                throw new IllegalArgumentException(
                        "未能从「" + filename + "」解析出有效文本，请确认文件内容（不支持扫描件/图片型 PDF）");
            }
            return ApiResponse.ok(Map.of("filename", filename, "chunks", chunks));
        } catch (IOException e) {
            throw new IllegalStateException("保存上传文件失败：" + e.getMessage());
        }
    }

    /**
     * 按来源（文件名）删除知识片段，同时从"已加载来源"中移除，便于重新入库。
     *
     * @param source 来源文件名
     * @return 被删除的来源
     */
    @DeleteMapping
    public ApiResponse<Map<String, Object>> deleteBySource(@RequestParam String source) {
        loader.deleteBySource(source);
        return ApiResponse.ok(Map.of("deletedSource", source));
    }

    /**
     * 知识库状态：向量库类型/实现类/表名/向量条数、已加载来源、切分与召回参数等。
     *
     * @return 状态快照
     */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        File file = new File(props.getVectorStorePath());
        Map<String, Object> stat = new LinkedHashMap<>();
        stat.put("vectorStoreType", vectorStoreType);
        stat.put("vectorStoreClass", vectorStore.getClass().getSimpleName());
        stat.put("vectorStoreTable", migrationService.qualifiedTable());
        stat.put("vectorCount", migrationService.countVectors());
        stat.put("loaded", loader.isLoaded());
        stat.put("documents", props.getDocuments().stream().map(Resource::getFilename).toList());
        stat.put("topK", props.getTopK());
        stat.put("similarityThreshold", props.getSimilarityThreshold());
        stat.put("maxMessages", props.getMaxMessages());
        stat.put("docDir", Path.of(props.getDocDir()).toAbsolutePath().normalize().toString());
        stat.put("sources", loader.listSources());
        stat.put("loadedSources", loader.getLoadedSources());
        stat.put("legacyVectorStorePath", file.getAbsolutePath());
        stat.put("legacyVectorStoreExists", file.exists());
        return ApiResponse.ok(stat);
    }

    /**
     * 一次性迁移：把旧 SimpleVectorStore 的 JSON 快照导入 PgVector 向量表。
     * 幂等，可重复调用（主键冲突的记录会被跳过）。
     *
     * @return 迁移报告，见 {@link VectorStoreMigrationService#migrate()}
     */
    @PostMapping("/migrate")
    public ApiResponse<Map<String, Object>> migrate() {
        return ApiResponse.ok(migrationService.migrate());
    }

    /**
     * 把文档裁剪成接口出参：分数、来源、正文摘要。
     * <p>正截断到 200 字，避免调试接口把整篇文档塞回浏览器。</p>
     *
     * @param doc 召回的文档
     * @return 出参 Map
     */
    private Map<String, Object> toView(Document doc) {
        String text = doc.getText() == null ? "" : doc.getText();
        return Map.of(
                "score", doc.getScore() == null ? 0d : doc.getScore(),
                "source", String.valueOf(doc.getMetadata().getOrDefault("source", "unknown")),
                "snippet", text.length() > 200 ? text.substring(0, 200) + "..." : text);
    }
}
