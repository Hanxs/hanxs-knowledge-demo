package com.example.knowledge.rag;

import com.example.knowledge.conf.RagProperties;
import com.pgvector.PGvector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 一次性数据迁移：把旧版 {@code SimpleVectorStore} 的 JSON 快照批量写入 PgVector 向量表。
 *
 * <p><b>为什么不用 {@code vectorStore.add(...)}？</b></p>
 * Spring AI 2.0 的 {@code Document} 已不再携带 embedding 字段，{@code add()} 会对每个片段
 * 重新调用 Embedding 接口。旧 JSON 里已经存好了 1024 维向量，直接写库可以：</p>
 * <ul>
 *   <li>保留原始向量，避免因模型版本差异导致检索结果漂移；</li>
 *   <li>不消耗 Embedding 配额、不依赖外部网络；</li>
 *   <li>批量提交，速度快几个数量级。</li>
 * </ul>
 *
 * <p>写入语句与 {@code PgVectorStore} 自身的 doAdd 保持一致
 * （{@code id / content / metadata / embedding} 四列），并使用 {@code ON CONFLICT (id) DO NOTHING}
 * 保证可重复执行（幂等）。</p>
 */
@Slf4j
@Service
public class VectorStoreMigrationService {

    /** 仅允许常规标识符，防止拼 SQL 时被注入 */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;
    private final RagProperties props;
    private final ObjectMapper objectMapper;

    private final String vectorStoreType;
    private final String schemaName;
    private final String tableName;
    private final String idType;

    public VectorStoreMigrationService(ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
                                       RagProperties props,
                                       ObjectMapper objectMapper,
                                       @Value("${spring.ai.vectorstore.type:pgvector}") String vectorStoreType,
                                       @Value("${spring.ai.vectorstore.pgvector.schema-name:public}") String schemaName,
                                       @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName,
                                       @Value("${spring.ai.vectorstore.pgvector.id-type:UUID}") String idType) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
        this.props = props;
        this.objectMapper = objectMapper;
        this.vectorStoreType = vectorStoreType;
        this.schemaName = requireIdentifier(schemaName, "schema-name");
        this.tableName = requireIdentifier(tableName, "table-name");
        this.idType = StringUtils.hasText(idType) ? idType.trim().toUpperCase() : "UUID";
    }

    /**
     * 执行迁移。
     *
     * @return 迁移报告：status / message / read / inserted / skipped / invalid / total / source
     */
    public Map<String, Object> migrate() {
        assertPgVectorMode();

        File source = resolveSourceFile();
        if (!source.isFile() || source.length() == 0) {
            log.warn("未找到迁移数据源文件: {}", source.getAbsolutePath());
            return report("SKIPPED", "未找到迁移数据源文件: " + source.getAbsolutePath(),
                    0, 0, 0, 0, source, 0);
        }

        JdbcTemplate jdbc = requireJdbcTemplate();
        if (!tableExists(jdbc)) {
            throw new IllegalStateException("向量表 " + qualifiedTable() + " 不存在。"
                    + "请确认 spring.ai.vectorstore.pgvector.initialize-schema=true 已生效，"
                    + "或手动执行 scripts/pgvector-init.sql 建表后再迁移。");
        }

        long before = countVectors();
        if (props.getMigrate().isSkipIfNotEmpty() && before > 0) {
            log.info("向量表 {} 已有 {} 条数据，按配置跳过迁移", qualifiedTable(), before);
            return report("SKIPPED",
                    "向量表已有 " + before + " 条数据，按 app.rag.migrate.skip-if-not-empty=true 跳过迁移",
                    0, 0, (int) before, 0, source, before);
        }

        Parsed parsed;
        try {
            parsed = parse(source);
        } catch (Exception e) {
            throw new IllegalStateException("解析迁移源文件失败: " + e.getMessage(), e);
        }

        if (parsed.batch().isEmpty()) {
            log.warn("迁移源文件 {} 中没有可导入的有效向量记录", source.getAbsolutePath());
            return report("SKIPPED", "源文件中没有可导入的有效向量记录",
                    parsed.read(), 0, parsed.invalid(), 0, source, before);
        }

        log.info("开始迁移：{} -> {}，共 {} 条有效记录（跳过无效 {} 条），批量写入 {} 条/批",
                source.getAbsolutePath(), qualifiedTable(), parsed.batch().size(), parsed.invalid(), 1000);

        int inserted = 0;
        int skipInvalid = parsed.invalid();
        int batchSize = 1000;
        for (int from = 0; from < parsed.batch().size(); from += batchSize) {
            List<Object[]> slice = parsed.batch().subList(from, Math.min(from + batchSize, parsed.batch().size()));
            int[] rows = jdbc.batchUpdate(insertSql(), slice);
            inserted += Arrays.stream(rows).filter(r -> r > 0).sum();
        }

        long after = countVectors();
        int duplicate = parsed.batch().size() - inserted;
        log.info("迁移完成：读取 {} 条，写入/更新 {} 条，主键重复跳过 {} 条，无效跳过 {} 条，表内现有 {} 条",
                parsed.read(), inserted, duplicate, skipInvalid, after);

        if (!props.getMigrate().isKeepSourceFile()) {
            backup(source);
        }

        return report("SUCCESS",
                "迁移完成：写入 " + inserted + " 条，跳过重复 " + duplicate + " 条，跳过无效 " + skipInvalid + " 条",
                parsed.read(), inserted, duplicate, skipInvalid, source, after);
    }

    /** 向量表当前记录数（非 pgvector 模式或表不存在时返回 -1）。 */
    public long countVectors() {
        if (!isPgVectorMode()) {
            return -1L;
        }
        JdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        if (jdbc == null || !tableExists(jdbc)) {
            return -1L;
        }
        Long count = jdbc.queryForObject("SELECT count(*) FROM " + qualifiedTable(), Long.class);
        return count == null ? 0L : count;
    }

    /**
     * 向量表中已入库的全部来源文件名（{@code distinct metadata ->> 'source'}）。
     *
     * <p>用于应用启动时回填"已加载来源"，让增量加载逻辑在持久化向量库下依然成立，
     * 避免每次重启都重复向量化全部文档。非 pgvector 模式或表不存在时返回空集合。</p>
     */
    public Set<String> distinctSources() {
        if (!isPgVectorMode()) {
            return Set.of();
        }
        JdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        if (jdbc == null || !tableExists(jdbc)) {
            return Set.of();
        }
        List<String> sources = jdbc.queryForList(
                "SELECT DISTINCT metadata ->> 'source' FROM " + qualifiedTable()
                        + " WHERE metadata ->> 'source' IS NOT NULL", String.class);
        return sources.stream().filter(StringUtils::hasText).collect(Collectors.toUnmodifiableSet());
    }

    public String qualifiedTable() {
        return schemaName + "." + tableName;
    }

    public boolean isPgVectorMode() {
        return "pgvector".equalsIgnoreCase(vectorStoreType);
    }

    // -------------------- 内部实现 --------------------

    private void assertPgVectorMode() {
        if (!isPgVectorMode()) {
            throw new IllegalStateException("当前 spring.ai.vectorstore.type=" + vectorStoreType
                    + "，数据迁移仅在 pgvector 模式下可用");
        }
    }

    private JdbcTemplate requireJdbcTemplate() {
        JdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        if (jdbc == null) {
            throw new IllegalStateException("未找到 JdbcTemplate，请确认已配置 spring.datasource 并引入 PgVector starter");
        }
        return jdbc;
    }

    /** 迁移源文件：优先 app.rag.migrate.source-file，其次 app.rag.vector-store-path */
    private File resolveSourceFile() {
        String configured = props.getMigrate().getSourceFile();
        String path = StringUtils.hasText(configured) ? configured : props.getVectorStorePath();
        return Path.of(path).toAbsolutePath().normalize().toFile();
    }

    private boolean tableExists(JdbcTemplate jdbc) {
        Boolean exists = jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, qualifiedTable());
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 与 PgVectorStore 的 INSERT 保持同构；{@code id} 列类型由 id-type 决定，UUID 时显式转型。
     */
    private String insertSql() {
        String idPlaceholder = "UUID".equals(idType) ? "?::uuid" : "?";
        return "INSERT INTO " + qualifiedTable()
                + " (id, content, metadata, embedding) VALUES ("
                + idPlaceholder + ", ?, ?::jsonb, ?) ON CONFLICT (id) DO NOTHING";
    }

    /**
     * 解析 SimpleVectorStore 的 JSON 结构：
     * <pre>
     * {
     *   "&lt;id&gt;": { "id": "...", "text": "...", "metadata": {...}, "embedding": [0.01, ...] }
     * }
     * </pre>
     */
    private Parsed parse(File source) {
        JsonNode root = objectMapper.readTree(source);
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("源文件不是合法的 SimpleVectorStore 快照（应为 {id: {text, metadata, embedding}} 对象）");
        }
        List<Object[]> batch = new ArrayList<>();
        int read = 0;
        int invalid = 0;
        for (Map.Entry<String, JsonNode> entry : root.properties()) {
            read++;
            JsonNode doc = entry.getValue();
            JsonNode textNode = doc.path("text");
            String text = textNode.isString() ? textNode.stringValue() : null;
            JsonNode embedding = doc.path("embedding");
            if (!StringUtils.hasText(text) || !embedding.isArray() || embedding.isEmpty()) {
                invalid++;
                continue;
            }
            JsonNode idNode = doc.path("id");
            String id = idNode.isString() ? idNode.stringValue() : entry.getKey();
            String safeId = normalizeId(id);
            String metadataJson = doc.path("metadata").isObject()
                    ? objectMapper.writeValueAsString(doc.path("metadata"))
                    : "{}";
            List<Float> vector = new ArrayList<>(embedding.size());
            for (JsonNode n : embedding) {
                vector.add(n.floatValue());
            }
            batch.add(new Object[]{safeId, text, metadataJson, new PGvector(vector)});
        }
        return new Parsed(read, invalid, batch);
    }

    /** id-type=UUID 时必须是合法 UUID，否则重新生成，避免整批插入失败 */
    private String normalizeId(String id) {
        if (!"UUID".equals(idType)) {
            return id;
        }
        try {
            return UUID.fromString(id).toString();
        } catch (IllegalArgumentException e) {
            String generated = UUID.randomUUID().toString();
            log.debug("id「{}」不是合法 UUID，已替换为 {}", id, generated);
            return generated;
        }
    }

    private void backup(File source) {
        Path bak = source.toPath().resolveSibling(source.getName() + ".bak");
        try {
            Files.move(source.toPath(), bak, StandardCopyOption.REPLACE_EXISTING);
            log.info("迁移源文件已备份为 {}", bak);
        } catch (IOException e) {
            log.warn("备份迁移源文件失败: {}", e.getMessage());
        }
    }

    private static String requireIdentifier(String value, String name) {
        if (!StringUtils.hasText(value) || !SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("非法的 spring.ai.vectorstore.pgvector." + name + " 配置: " + value);
        }
        return value;
    }

    private Map<String, Object> report(String status, String message, int read, int inserted,
                                       int skipped, int invalid, File source, long total) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", status);
        map.put("message", message);
        map.put("source", source.getAbsolutePath());
        map.put("table", qualifiedTable());
        map.put("read", read);
        map.put("inserted", inserted);
        map.put("skipped", skipped);
        map.put("invalid", invalid);
        map.put("totalInTable", total);
        return map;
    }

    /** 解析结果 */
    private record Parsed(int read, int invalid, List<Object[]> batch) {
    }
}
