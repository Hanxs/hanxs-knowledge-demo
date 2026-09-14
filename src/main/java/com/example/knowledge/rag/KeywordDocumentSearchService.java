package com.example.knowledge.rag;

import com.example.knowledge.conf.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 混合检索的「关键词路」：直接用 PG 原生全文检索查向量表的内容列。
 *
 * <p><b>为什么要自己写 SQL，而不是用 VectorStore？</b>
 * Spring AI 的 {@code VectorStore} 抽象只有语义检索能力；而 {@code similaritySearch}
 * 内部只按 embedding 距离排序，无法表达 {@code @@ (tsvector 匹配)} 这类谓词。
 * 关键词路必须触达 PG 的 tsvector 能力，因此这里走 JDBC 原生 SQL。</p>
 *
 * <p><b>关于中文分词（务必了解）</b></p>
 * PostgreSQL 没有内置中文分词器，{@code 'simple'} 配置只按空格/标点切词，
 * 一段中文会整体变成一个词元，所以纯 {@code to_tsvector(content) @@ plainto_tsquery(?)}：
 * <pre>
 *   查询 '考勤管理制度' -> 0 行
 *   查询 'Updates were rejected' -> 1 行
 * </pre>
 * 因此本实现采用「双通道关键词谓词」并用 OR 连接：
 * <ul>
 *   <li><b>FTS 通道</b>：{@code to_tsvector} @@ {@code plainto_tsquery}，走
 *       {@code idx_vector_store_content_fts} GIN 索引；对英文、数字、代码标识符效果好，
 *       并且能用 {@code ts_rank_cd} 给出真正的相关度打分。</li>
 *   <li><b>子串通道</b>：{@code content ILIKE '%...%'}，走
 *       {@code idx_vector_store_content_trgm} GIN(pg_trgm) 索引；中文由此兜底。</li>
 * </ul>
 * 若后续接入 zhparser / pg_jieba，只需改 {@code app.rag.hybrid.fts-config}，
 * FTS 通道即可接管中文，子串通道可关闭。
 */
@Slf4j
@Service
public class KeywordDocumentSearchService {

    /** 表/schema 名白名单，防止拼 SQL 时被注入 */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** ILIKE 通配符转义字符 */
    private static final String LIKE_ESCAPE = "\\";
    /** 用于把长查询再切成更短的词/短语，提升中文长句召回 */
    private static final Pattern TOKEN_SEPARATOR =
            Pattern.compile("[\\s,，。、;；:：!！?？()（）\\[\\]【】\"'“”‘’/\\\\|+*~`^&%$#@]+");
    /** 单个查询最多切成多少个词，避免 SQL 参数爆炸 */
    private static final int MAX_TOKENS = 8;
    /** 子串命中次数的贡献权重（与 ts_rank_cd 同量级换算） */
    private static final double HIT_WEIGHT = 0.02;
    /** 计分时子串命中次数的上限，避免长文档靠堆词刷分 */
    private static final int MAX_HITS = 10;

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;
    private final RagProperties props;
    private final ObjectMapper objectMapper;

    private final String vectorStoreType;
    private final String schemaName;
    private final String tableName;

    /**
     * 构造关键词检索服务。
     *
     * <p>表名与 schema 名来自配置，会被拼进 SQL，因此在构造期就用
     * {@link #requireIdentifier} 做白名单校验，从源头杜绝注入。</p>
     *
     * @param jdbcTemplateProvider JDBC 模板；非 pgvector 模式下容器里没有数据源，
     *                             用 {@code ObjectProvider} 延迟取用避免启动失败
     * @param props                混合检索配置
     * @param objectMapper         JSON 解析器，用于还原 metadata 列
     * @param vectorStoreType      向量库类型，仅 {@code pgvector} 时本服务可用
     * @param schemaName           向量表所在 schema
     * @param tableName            向量表名
     */
    public KeywordDocumentSearchService(ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
                                        RagProperties props,
                                        ObjectMapper objectMapper,
                                        @Value("${spring.ai.vectorstore.type:pgvector}") String vectorStoreType,
                                        @Value("${spring.ai.vectorstore.pgvector.schema-name:public}") String schemaName,
                                        @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
        this.props = props;
        this.objectMapper = objectMapper;
        this.vectorStoreType = vectorStoreType;
        this.schemaName = requireIdentifier(schemaName, "schema-name");
        this.tableName = requireIdentifier(tableName, "table-name");
    }

    /**
     * 关键词路是否可用：仅 pgvector 模式、存在 JdbcTemplate、且向量表已建时为真。
     * 不可用时 {@link #search} 返回空列表，由上层降级为纯向量检索。
     */
    public boolean isAvailable() {
        return isPgVectorMode() && jdbcTemplateProvider.getIfAvailable() != null
                && tableExists(jdbcTemplateProvider.getIfAvailable());
    }

    /**
     * 关键词召回，按相关度降序返回最多 {@code topK} 条。
     *
     * @param query 用户问题
     * @param topK  召回条数
     * @return 命中的文档（分数写入 {@link Document#getScore()}），按分数降序；不可用时返回空列表
     */
    public List<Document> search(String query, int topK) {
        List<Document> empty = List.of();
        if (!StringUtils.hasText(query) || topK <= 0) {
            return empty;
        }
        JdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        if (jdbc == null || !isPgVectorMode() || !tableExists(jdbc)) {
            log.debug("关键词路不可用（type={}, jdbc={}），返回空结果", vectorStoreType, jdbc != null);
            return empty;
        }

        RagProperties.Hybrid cfg = props.getHybrid();
        String raw = query.trim();
        List<String> tokens = resolveTokens(raw, cfg);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("cfg", cfg.getFtsConfig())
                .addValue("raw", raw)
                .addValue("hitsWeight", HIT_WEIGHT)
                .addValue("maxHits", MAX_HITS)
                .addValue("lim", topK);
        for (int i = 0; i < tokens.size(); i++) {
            params.addValue("tok" + i, likePattern(tokens.get(i)));
        }

        try {
            return new NamedParameterJdbcTemplate(jdbc).query(
                    buildSql(cfg, likeCondition(tokens)),
                    params,
                    (rs, rowNum) -> Document.builder()
                            .id(rs.getString("id"))
                            .text(rs.getString("content"))
                            .metadata(parseMetadata(rs.getString("metadata")))
                            .score(rs.getDouble("score"))
                            .build());
        } catch (Exception e) {
            // 关键词路失败不应拖垮主流程：由上层降级为纯向量检索
            log.warn("PG 全文检索失败，本次仅使用向量召回: {}", e.getMessage());
            return empty;
        }
    }

    // -------------------- SQL 构造 --------------------

    /**
     * 构造关键词查询 SQL。
     *
     * <p>打分公式 {@code score = ts_rank_cd + min(子串命中次数, 10) * 0.02}：
     * FTS 通道给出真正的词频/覆盖度打分，子串通道只作为补充，
     * 因此即使中文场景下 FTS 恒为 0，文档之间仍可按命中次数排出先后。</p>
     *
     * @param cfg            混合检索配置
     * @param likePredicate  子串通道的 OR 条件，为空表示不做子串匹配
     * @return 带命名参数的 SQL
     */
    private String buildSql(RagProperties.Hybrid cfg, String likePredicate) {
        // query-or-expansion=true 时把 plainto_tsquery 的 AND 语义改写成 OR：
        // plainto_tsquery 只会输出扁平的 'a' & 'b'，替换为 '|' 后结构依然合法，
        // 召回率明显上升（代价是精度略有下降，由 RRF 再排序兜住）。
        String cfgExpr = "CAST(:cfg AS regconfig)";
        String rawExpr = "CAST(:raw AS text)";
        String tsqExpression = cfg.isQueryOrExpansion()
                ? "to_tsquery(" + cfgExpr + ", regexp_replace(plainto_tsquery(" + cfgExpr + ", " + rawExpr + ")::text, '&', '|', 'g'))"
                : "plainto_tsquery(" + cfgExpr + ", " + rawExpr + ")";
        String whereClause = "WHERE c.fts_rank > 0"
                + (StringUtils.hasText(likePredicate) ? " OR (" + likePredicate + ")" : "");
        // ⚠️ 所有绑定参数都要显式 CAST：
        // JDBC 传过来的是未知类型的占位符，PG 在 to_tsvector($1, ...)、least(int, $2)
        // 这类位置无法推断类型，会直接抛 "could not determine data type of parameter"。
        return """
                WITH params AS (
                  SELECT %s AS q,
                         %s AS tsq
                )
                SELECT c.id, c.content, c.metadata,
                       (c.fts_rank + least(c.hits, CAST(:maxHits AS int)) * CAST(:hitsWeight AS float8)) AS score
                FROM (
                  SELECT vs.id::text AS id,
                         vs.content AS content,
                         vs.metadata::text AS metadata,
                         ts_rank_cd(to_tsvector(%s, vs.content), p.tsq, 32) AS fts_rank,
                         CASE WHEN p.q = '' THEN 0
                              ELSE (length(vs.content) - length(replace(lower(vs.content), lower(p.q), '')))
                                   / length(p.q) END AS hits
                  FROM %s vs CROSS JOIN params p
                ) c
                %s
                ORDER BY score DESC, length(c.content) ASC
                LIMIT CAST(:lim AS int)
                """.formatted(rawExpr, tsqExpression, cfgExpr, qualifiedTable(), whereClause);
    }

    /**
     * 子串通道的 OR 条件：{@code content ILIKE :tok0 ESCAPE '\' OR content ILIKE :tok1 ...}。
     * 为空时返回空字符串，调用方据此决定是否拼这一段。
     *
     * @param tokens 参与子串匹配的词，按 {@code tok0/tok1/...} 依次绑定命名参数
     * @return OR 连接的条件表达式；tokens 为空时返回空串
     */
    private String likeCondition(List<String> tokens) {
        if (tokens.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) {
                sb.append(" OR ");
            }
            sb.append("c.content ILIKE CAST(:tok").append(i).append(" AS text) ESCAPE '")
                    .append(LIKE_ESCAPE).append("'");
        }
        return sb.toString();
    }

    // -------------------- 文本处理 --------------------

    /**
     * 决定用哪些串做子串匹配。
     * <ul>
     *   <li>关闭子串兜底且关闭分词 -> 不做子串匹配（纯 FTS）</li>
     *   <li>开启分词 -> 用拆分后的各词</li>
     *   <li>仅开启兜底 -> 用原始查询串整体</li>
     * </ul>
     *
     * @param raw  原始查询串
     * @param cfg  混合检索配置
     * @return 参与子串匹配的词列表；关闭兜底时返回空列表
     */
    private List<String> resolveTokens(String raw, RagProperties.Hybrid cfg) {
        if (!cfg.isSubstringFallback()) {
            return List.of();
        }
        if (!cfg.isSubstringTokenSplit()) {
            return List.of(raw);
        }
        List<String> tokens = new ArrayList<>();
        for (String t : TOKEN_SEPARATOR.split(raw)) {
            if (StringUtils.hasText(t) && !tokens.contains(t)) {
                tokens.add(t);
            }
            if (tokens.size() >= MAX_TOKENS) {
                break;
            }
        }
        // 整句本身也参与匹配（查询可能就是一个词组），但要保证不重复
        if (!tokens.contains(raw)) {
            tokens.add(0, raw);
        }
        return tokens;
    }

    /**
     * 转义 ILIKE 通配符并包成 {@code %xxx%} 模式。
     * <p>用户输入里的 {@code %}、{@code _}、{@code \} 若不转义会被当作通配符，
     * 轻则误召回，重则退化成全表匹配。</p>
     *
     * @param token 原词
     * @return 可直接绑定的 LIKE 模式
     */
    private static String likePattern(String token) {
        String escaped = token.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    /**
     * 把 json/jsonb 元数据列还原成 Map（Jackson 3 API）。
     * <p>解析失败只丢弃元数据、不丢弃文档本身，避免一条脏数据毁掉整次召回。</p>
     *
     * @param json 元数据列的字符串形式
     * @return 元数据 Map；为空或解析失败时返回空 Map
     */
    private Map<String, Object> parseMetadata(String json) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (!StringUtils.hasText(json)) {
            return meta;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isObject()) {
                return meta;
            }
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                JsonNode v = e.getValue();
                if (v.isNull()) {
                    continue;
                }
                meta.put(e.getKey(), v.isNumber() ? v.numberValue() : v.stringValue());
            }
        } catch (JacksonException e) {
            log.debug("元数据解析失败，忽略: {}", e.getMessage());
        }
        return meta;
    }

    // -------------------- 基础设施 --------------------

    /**
     * 判断向量表是否存在。
     * <p>用 {@code to_regclass} 而非查系统表：表不存在时它返回 NULL 而不报错，
     * 省掉一次异常控制流。任何异常都按"不存在"处理，不影响主流程。</p>
     *
     * @param jdbc JDBC 模板
     * @return 表存在返回 true
     */
    private boolean tableExists(JdbcTemplate jdbc) {
        try {
            Boolean exists = jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, qualifiedTable());
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 当前是否为 pgvector 模式。
     * <p>{@code simple} 模式下向量在内存里，没有对应的 PG 表，关键词路无意义。</p>
     *
     * @return 是 pgvector 模式返回 true
     */
    private boolean isPgVectorMode() {
        return "pgvector".equalsIgnoreCase(vectorStoreType);
    }

    /**
     * 限定的表名，形如 {@code public.vector_store}。
     * <p>两个组成部分都已在构造期通过 {@link #requireIdentifier} 校验。</p>
     *
     * @return 可直接拼进 SQL 的限定表名
     */
    public String qualifiedTable() {
        return schemaName + "." + tableName;
    }

    /**
     * 校验 SQL 标识符合法性，防止配置被注入恶意片段。
     *
     * @param value 待校验值
     * @param name  配置项名，仅用于报错信息
     * @return 校验通过的原值
     * @throws IllegalArgumentException 值为空或不符合标识符规则时抛出
     */
    private static String requireIdentifier(String value, String name) {
        if (!StringUtils.hasText(value) || !SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("非法的 spring.ai.vectorstore.pgvector." + name + " 配置: " + value);
        }
        return value;
    }
}
