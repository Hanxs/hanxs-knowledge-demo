package com.example.knowledge.rag;

import com.example.knowledge.conf.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 知识库加载器：读取文档 -> 补充元数据 -> 切分 -> 向量化入库 -> 持久化。
 *
 * <p>支持的格式：</p>
 * <ul>
 *   <li>{@code .txt / .md} 等纯文本 —— TextReader</li>
 *   <li>{@code .pdf} —— PagePdfDocumentReader（PDFBox）</li>
 *   <li>{@code .doc / .docx / .xlsx / .pptx / .odt / .rtf} —— TikaDocumentReader</li>
 * </ul>
 *
 * <p>加载策略：按"来源文件名"增量加载，已加载过的来源不会重复入库；
 * {@code force=true} 时全量重载，且会先清除该来源的历史片段，避免产生重复向量。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseLoader {

    private final VectorStore vectorStore;
    private final DocumentTransformer textSplitter;
    private final RagProperties props;

    private final AtomicBoolean loaded = new AtomicBoolean(false);
    private final Set<String> loadedSources = ConcurrentHashMap.newKeySet();

    /** 目录扫描时识别的扩展名 */
    private static final Set<String> SUPPORTED =
            Set.of("txt", "md", "pdf", "doc", "docx", "xlsx", "pptx", "odt", "rtf");

    /** 需要交给 Tika 解析的办公文档扩展名 */
    private static final Set<String> OFFICE =
            Set.of("doc", "docx", "xlsx", "pptx", "odt", "rtf");

    /**
     * 加载全部知识文档。
     *
     * @param force false=增量（仅加载新来源）；true=全量重载
     */
    public synchronized int loadAll() {
        return loadAll(false);
    }

    /**
     * 加载全部知识文档（配置声明 + 文档目录自动发现，按文件名去重）。
     * <p>方法加 synchronized：入库涉及"先删后写"，并发触发会互相清掉对方的数据。</p>
     *
     * @param force false=增量（跳过已加载来源）；true=全量重载
     * @return 本次入库的片段总数
     */
    public synchronized int loadAll(boolean force) {
        int total = 0;
        for (Resource resource : allResources()) {
            String name = filenameOf(resource);
            if (!force && loadedSources.contains(name)) {
                continue;
            }
            int n = load(resource);
            if (n > 0) {
                loadedSources.add(name);
            }
            total += n;
        }
        if (total > 0) {
            persist();
        }
        loaded.set(true);
        log.info("知识库加载完成（force={}），本次入库 {} 个片段", force, total);
        return total;
    }

    /**
     * 加载单个资源：先清除该来源的历史片段，再写入，保证重复加载不会产生重复向量。
     */
    public int load(Resource resource) {
        String filename = filenameOf(resource);
        if (!resource.exists()) {
            log.warn("知识文档不存在，已跳过: {}", filename);
            return 0;
        }
        List<Document> documents;
        try {
            documents = createReader(resource, filename).get();
        } catch (Exception e) {
            log.error("解析文档失败，已跳过: {} - {}", filename, e.getMessage());
            return 0;
        }
        List<Document> chunks = textSplitter.apply(enrich(documents, filename));
        if (chunks.isEmpty()) {
            log.warn("文档切分后无有效内容: {}", filename);
            return 0;
        }
        deleteBySource(filename);
        vectorStore.write(chunks);
        log.info("入库完成: {} -> 原文 {} 段, 切分 {} 片", filename, documents.size(), chunks.size());
        return chunks.size();
    }

    /**
     * 保存上传的文件到文档目录，并立即加载入库。
     */
    public int saveAndLoad(String originalFilename, byte[] content) throws IOException {
        Path dir = Path.of(props.getDocDir()).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        // getFileName() 用于剥离路径，防止目录穿越
        Path target = dir.resolve(Paths.get(originalFilename).getFileName().toString());
        Files.write(target, content);
        log.info("已保存上传文件: {}", target);

        int chunks = load(new FileSystemResource(target));
        if (chunks > 0) {
            loadedSources.add(target.getFileName().toString());
            persist();
        }
        loaded.set(true);
        return chunks;
    }

    /**
     * 把一段纯文本直接入库（用于接口动态灌数据，不需要落文件）。
     *
     * @param textContent 正文
     * @param metadata    附加元数据，为空时只带切分器默认字段
     * @return 入库的片段数
     */
    public int loadText(String textContent, Map<String, Object> metadata) {
        Document doc = new Document(textContent, metadata == null ? new HashMap<>() : metadata);
        List<Document> chunks = textSplitter.apply(List.of(doc));
        vectorStore.write(chunks);
        persist();
        log.info("动态文本已入库，共 {} 个片段", chunks.size());
        return chunks.size();
    }

    /**
     * 知识库是否已加载过（内存态，重启后由 {@link #primeLoadedSources} 回填）。
     *
     * @return 已加载返回 true
     */
    public boolean isLoaded() {
        return loaded.get();
    }

    /**
     * 把向量库中已存在的来源登记为"已加载"。
     *
     * <p>{@code loadedSources} 是内存态，应用重启后为空；而 PgVector 里的向量是持久化的，
     * 若不登记就会在每次启动时对全部文档重新切分 + 重新调用 Embedding 接口
     * （先按来源删除再写入，不会产生重复数据，但白白消耗配额）。
     * 启动时从向量表回填真实来源即可让增量逻辑在持久化场景下继续成立。</p>
     */
    public void primeLoadedSources(Collection<String> sources) {
        if (sources == null || sources.isEmpty()) {
            return;
        }
        loadedSources.addAll(sources);
        loaded.set(true);
    }

    /**
     * 当前已入库的来源快照。
     *
     * @return 不可变副本，避免外部修改内部状态
     */
    public Set<String> getLoadedSources() {
        return Set.copyOf(loadedSources);
    }

    /**
     * 统一的知识来源视图：合并「配置声明」「文档目录」「向量库已入库」三类来源，
     * 保证上传的文档也能在页面中展示。
     *
     * @return 每个来源的 name / origin / ext / size / loaded
     */
    public List<Map<String, Object>> listSources() {
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        for (Resource r : safeDocuments()) {
            map.put(filenameOf(r), sourceView(filenameOf(r), "配置", r));
        }
        // 上传的文件与手动放入目录的文件
        for (Resource r : scanDocDir()) {
            map.putIfAbsent(filenameOf(r), sourceView(filenameOf(r), "目录", r));
        }
        // 已入库但源文件已不存在的来源（避免列表中丢失）
        for (String s : loadedSources) {
            map.putIfAbsent(s, Map.of(
                    "name", s, "origin", "向量库", "ext", extOf(s), "size", 0L, "loaded", true));
        }
        return new ArrayList<>(map.values());
    }

    private Map<String, Object> sourceView(String name, String origin, Resource resource) {
        long size = 0L;
        try {
            size = resource.contentLength();
        } catch (IOException ignored) {
            // 大小获取失败时按 0 处理，不影响列表展示
        }
        return Map.of(
                "name", name,
                "origin", origin,
                "ext", extOf(name),
                "size", size,
                "loaded", loadedSources.contains(name));
    }

    /**
     * 按来源删除全部知识片段。
     */
    public void deleteBySource(String source) {
        try {
            vectorStore.delete(new FilterExpressionBuilder().eq("source", source).build());
        } catch (Exception e) {
            log.debug("清理来源 {} 的历史片段失败（可能尚不存在）: {}", source, e.getMessage());
        }
    }

    // -------------------- 内部实现 --------------------

    /** 配置声明的文档 + 文档目录下自动发现的文件（按文件名去重） */
    private List<Resource> allResources() {
        Map<String, Resource> map = new LinkedHashMap<>();
        for (Resource r : safeDocuments()) {
            map.putIfAbsent(filenameOf(r), r);
        }
        for (Resource r : scanDocDir()) {
            map.putIfAbsent(filenameOf(r), r);
        }
        return new ArrayList<>(map.values());
    }

    private List<Resource> safeDocuments() {
        return props.getDocuments() == null ? List.of() : props.getDocuments();
    }

    /** 扫描文档目录下所有受支持格式的文件 */
    private List<Resource> scanDocDir() {
        File dir = Path.of(props.getDocDir()).toAbsolutePath().normalize().toFile();
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return List.of();
        }
        return Arrays.stream(files)
                .filter(File::isFile)
                .filter(f -> SUPPORTED.contains(extOf(f.getName())))
                .sorted(Comparator.comparing(File::getName))
                .map(f -> (Resource) new FileSystemResource(f))
                .toList();
    }

    private DocumentReader createReader(Resource resource, String filename) {
        String ext = extOf(filename);
        if ("pdf".equals(ext)) {
            return new PagePdfDocumentReader(resource);
        }
        if (OFFICE.contains(ext)) {
            return new TikaDocumentReader(resource);
        }
        return new TextReader(resource);
    }

    /** 为文档补充来源、类型、加载时间等元数据，便于按来源过滤与删除 */
    private List<Document> enrich(List<Document> documents, String filename) {
        String ext = extOf(filename);
        String loadedAt = LocalDateTime.now().toString();
        return documents.stream().map(doc -> {
            Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
            metadata.put("source", filename);
            metadata.put("type", ext);
            metadata.put("loadedAt", loadedAt);
            return new Document(doc.getText(), metadata);
        }).toList();
    }

    private String extOf(String filename) {
        if (filename == null) {
            return "";
        }
        int i = filename.lastIndexOf('.');
        return i < 0 ? "" : filename.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private String filenameOf(Resource resource) {
        String name = resource.getFilename();
        return (name == null || name.isBlank()) ? "unknown" : name;
    }

    /**
     * 向量库持久化。
     * <p>PgVector 模式下数据已写入 PostgreSQL，无需额外落盘；
     * 仅当回退到 {@link SimpleVectorStore}（内存实现）时才写 JSON 文件。</p>
     */
    private void persist() {
        if (!(vectorStore instanceof SimpleVectorStore simple)) {
            log.debug("当前向量库为 {}，数据已持久化，跳过 JSON 落盘", vectorStore.getClass().getSimpleName());
            return;
        }
        try {
            File file = Path.of(props.getVectorStorePath()).toAbsolutePath().normalize().toFile();
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }
            simple.save(file);
            log.info("向量库已持久化到 {}", file.getAbsolutePath());
        } catch (Exception e) {
            log.warn("向量库持久化失败: {}", e.getMessage());
        }
    }
}
