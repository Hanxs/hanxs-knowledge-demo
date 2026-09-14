package com.example.knowledge.controller;

import com.example.knowledge.common.ApiResponse;
import com.example.knowledge.conf.RagProperties;
import com.example.knowledge.rag.KnowledgeBaseLoader;
import com.example.knowledge.rag.VectorStoreMigrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
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
import java.util.LinkedHashMap;
import java.util.List;
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
    private final RagProperties props;

    @Value("${spring.ai.vectorstore.type:pgvector}")
    private String vectorStoreType;

    /**
     * 加载/重载知识库。force=true 时强制重载。
     */
    @PostMapping("/load")
    public ApiResponse<Map<String, Object>> load(@RequestParam(defaultValue = "false") boolean force) {
        int chunks = loader.loadAll(force);
        return ApiResponse.ok(Map.of("loadedChunks", chunks, "forced", force));
    }

    /**
     * 检索预览：查看某问题实际召回哪些知识片段，便于调参。
     */
    @GetMapping("/search")
    public ApiResponse<List<Map<String, Object>>> search(@RequestParam String q,
                                                         @RequestParam(required = false) Integer topK) {
        SearchRequest request = SearchRequest.builder()
                .query(q)
                .topK(topK == null ? props.getTopK() : topK)
                .similarityThreshold(props.getSimilarityThreshold())
                .build();
        List<Map<String, Object>> result = vectorStore.similaritySearch(request).stream()
                .map(this::toView)
                .toList();
        return ApiResponse.ok(result);
    }

    /**
     * 上传文档并入库。支持 txt / md / pdf / doc / docx / xlsx / pptx。
     * 文件保存在 app.rag.doc-dir 目录，后续会被自动发现，无需改配置或重启。
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
     * 按来源（文件名）删除知识片段。
     */
    @DeleteMapping
    public ApiResponse<Map<String, Object>> deleteBySource(@RequestParam String source) {
        loader.deleteBySource(source);
        return ApiResponse.ok(Map.of("deletedSource", source));
    }

    /**
     * 知识库状态。
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
     */
    @PostMapping("/migrate")
    public ApiResponse<Map<String, Object>> migrate() {
        return ApiResponse.ok(migrationService.migrate());
    }

    private Map<String, Object> toView(Document doc) {
        String text = doc.getText() == null ? "" : doc.getText();
        return Map.of(
                "score", doc.getScore() == null ? 0d : doc.getScore(),
                "source", String.valueOf(doc.getMetadata().getOrDefault("source", "unknown")),
                "snippet", text.length() > 200 ? text.substring(0, 200) + "..." : text);
    }
}
