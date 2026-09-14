package com.example.knowledge.rag;

import com.example.knowledge.conf.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索：向量路 + 关键词路双路召回，再用 RRF（倒数排名融合）合并排序。
 *
 * <p><b>为什么要混合？</b><br>
 * 纯向量检索擅长语义相近，但对精确词（型号、错误码、人名、制度条款序号）经常漏召回；
 * PG 原生全文检索擅长精确词，但对同义改写无能为力。两路互补，
 * 工程上比"二选一"稳定得多。</p>
 *
 * <p><b>为什么用 RRF 而不是加权求和？</b><br>
 * 两条路的分数不同量纲且不可比：向量路是余弦相似度（0~1，随模型变化），
 * 关键词路是 {@code ts_rank_cd}（0~1，随词频/文档长度飘）。直接加权需要反复调参，
 * 换个 Embedding 模型就得重调。RRF 只用<b>排名</b>：
 * <pre>
 *     score(d) = Σ_r  1 / (k + rank_r(d))      rank 从 1 开始，k 默认 60
 * </pre>
 * 与分数无关，天然免疫量纲差异；同时被两路召回到前面的文档会被显著抬高。</p>
 *
 * <p><b>降级策略</b>：关键词路不可用（非 pgvector 模式、表不存在、SQL 报错、
 * plain 参数缺失）时自动退化为纯向量检索，不影响主流程。</p>
 */
@Slf4j
@Service
public class HybridSearchService {

    private final VectorStore vectorStore;
    private final KeywordDocumentSearchService keywordSearch;
    private final RagProperties props;

    /**
     * 构造混合检索服务。
     *
     * @param vectorStore   向量库，用于第一路语义召回
     * @param keywordSearch PG 原生全文检索服务，用于第二路关键词召回
     * @param props         混合检索参数（召回条数、RRF 系数等）
     */
    public HybridSearchService(VectorStore vectorStore,
                               KeywordDocumentSearchService keywordSearch,
                               RagProperties props) {
        this.vectorStore = vectorStore;
        this.keywordSearch = keywordSearch;
        this.props = props;
    }

    /**
     * 单路：纯向量检索（混合未启用 / 关键词路不可用时的兜底）。
     *
     * <p>{@code app.rag.hybrid.vector-similarity-threshold} 大于 0 时按阈值过滤，
     * 否则不过滤——混合召回阶段提前砍掉结果，会让本可通过关键词路补齐的文档丢失，
     * 因此默认把过滤交给 RRF 之后的排序决定。</p>
     *
     * @param query 用户问题
     * @param topK  召回条数
     * @return 按相似度降序的文档
     */
    public List<Document> searchByVector(String query, int topK) {
        SearchRequest.Builder builder = SearchRequest.builder().query(query).topK(topK);
        double threshold = props.getHybrid().getVectorSimilarityThreshold();
        if (threshold > 0) {
            builder.similarityThreshold(threshold);
        } else {
            builder.similarityThresholdAll();
        }
        return vectorStore.similaritySearch(builder.build());
    }

    /**
     * 混合检索：双路召回 → RRF 融合 → 取 Top-N。
     *
     * @param query 用户问题
     * @return 融合结果，含最终文档列表与可观测的融合明细
     */
    public HybridResult search(String query) {
        return search(query, null);
    }

    /**
     * 混合检索，可临时覆盖最终返回条数。
     *
     * @param query             用户问题
     * @param finalTopKOverride 不为空时覆盖 {@code app.rag.hybrid.final-top-k}，便于接口临时调参
     * @return 融合结果，含最终文档列表与可观测的融合明细
     */
    public HybridResult search(String query, Integer finalTopKOverride) {
        RagProperties.Hybrid cfg = props.getHybrid();
        int finalTopK = finalTopKOverride != null && finalTopKOverride > 0
                ? finalTopKOverride
                : cfg.getFinalTopK();
        List<Document> empty = List.of();

        if (!StringUtils.hasText(query)) {
            return new HybridResult(empty, empty, empty, List.of(), false, false);
        }
        if (!cfg.isEnabled()) {
            List<Document> docs = searchByVector(query, props.getTopK());
            return new HybridResult(docs, docs, empty, List.of(), false, false);
        }

        // ---------- 第一路：向量召回 ----------
        List<Document> vectorDocs = safeVectorSearch(query, cfg.getVectorTopK());

        // ---------- 第二路：PG 原生全文检索召回 ----------
        List<Document> keywordDocs = List.of();
        boolean keywordAvailable = keywordSearch.isAvailable();
        if (keywordAvailable) {
            keywordDocs = keywordSearch.search(query, cfg.getKeywordTopK());
        } else {
            log.debug("关键词路不可用，本次降级为纯向量召回");
        }

        // 关键词路一条都没命中时，融合没有额外信息，直接返回向量结果
        if (keywordDocs.isEmpty()) {
            List<Document> docs = truncate(vectorDocs, finalTopK);
            return new HybridResult(docs, vectorDocs, keywordDocs, List.of(), keywordAvailable, true);
        }

        List<FusionItem> fused = fuse(vectorDocs, keywordDocs, cfg.getRrfK());
        List<FusionItem> top = truncate(fused, finalTopK);
        List<Document> documents = top.stream().map(FusionItem::document).toList();

        log.debug("混合检索：向量 {} 条 + 关键词 {} 条 -> 融合 {} 条，最终取 {} 条",
                vectorDocs.size(), keywordDocs.size(), fused.size(), documents.size());

        List<Map<String, Object>> trace = toTrace(top);
        return new HybridResult(documents, vectorDocs, keywordDocs, trace, keywordAvailable, false);
    }

    /**
     * RRF 融合。
     *
     * @param vectorDocs  按相似度降序
     * @param keywordDocs 按关键词相关度降序
     * @param k           平滑常数
     * @return 按 RRF 得分降序的融合列表
     */
    List<FusionItem> fuse(List<Document> vectorDocs, List<Document> keywordDocs, int k) {
        Map<String, FusionItem> acc = new LinkedHashMap<>();
        accumulate(acc, VectorRoute.VECTOR, vectorDocs, Math.max(k, 1));
        accumulate(acc, VectorRoute.KEYWORD, keywordDocs, Math.max(k, 1));

        return acc.values().stream()
                .sorted(Comparator
                        .comparingDouble(FusionItem::score).reversed()
                        .thenComparing(i -> bestRank(i))                 // 同分时比较最好名次
                        .thenComparing(FusionItem::key))                 // 保证排序稳定
                .toList();
    }

    /**
     * 把一路召回结果累加进融合表。
     *
     * <p>{@code docs} 必须已按该路的相关度降序排列，名次即下标 + 1；
     * 同一文档若已在表中（另一路也召回到）则分数叠加、名次各自记录。</p>
     *
     * @param acc   融合累计表，key 为文档主键
     * @param route 路线标识，取值见 {@link VectorRoute}
     * @param docs  该路召回的文档，按相关度降序
     * @param k     RRF 平滑常数
     */
    private void accumulate(Map<String, FusionItem> acc, String route, List<Document> docs, int k) {
        int rank = 1;
        for (Document doc : docs) {
            String key = docKey(doc, rank, route);
            FusionItem item = acc.computeIfAbsent(key, ck -> new FusionItem(ck, doc));
            item.add(1.0 / (k + rank), route, rank);
            rank++;
        }
    }

    /**
     * 融合主键：优先用文档 id。
     * <p>PgVectorStore 与关键词 SQL 都会返回数据库主键，因此两路可直接对齐。
     * 极端情况下（自定义实现不含 id）退化为内容前若干字符，避免全部合并成一条。</p>
     */
    private String docKey(Document doc, int rank, String route) {
        if (StringUtils.hasText(doc.getId())) {
            return doc.getId();
        }
        String content = doc.getText() == null ? "" : doc.getText();
        return "fallback:" + System.identityHashCode(doc) + ":" + rank + ":" + route + ":"
                + content.substring(0, Math.min(content.length(), 64));
    }

    /**
     * 取该文档在所有路线中最好的名次（数值最小）。
     * <p>用于 RRF 得分相同时的次序裁决：名次靠前的优先。</p>
     *
     * @param item 融合项
     * @return 最好名次；无名次时返回 {@link Integer#MAX_VALUE}
     */
    private static int bestRank(FusionItem item) {
        return item.ranks.values().stream().min(Integer::compareTo).orElse(Integer.MAX_VALUE);
    }

    /**
     * 生成融合明细，供管理接口排查"为什么召不回来"。
     *
     * @param items RRF 排序后的前 N 项
     * @return 每项含 id、RRF 得分、各路名次、来源与摘要
     */
    private List<Map<String, Object>> toTrace(List<FusionItem> items) {
        List<Map<String, Object>> trace = new ArrayList<>(items.size());
        for (FusionItem item : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.key());
            row.put("rrfScore", item.score());
            row.put("ranks", item.ranks());
            row.put("source", item.document().getMetadata().getOrDefault("source", "unknown"));
            row.put("snippet", abbreviate(item.document().getText(), 160));
            trace.add(row);
        }
        return trace;
    }

    // -------------------- 工具方法 --------------------

    /**
     * 带兜底的向量检索：Embedding 接口超时/配额异常时不让整个问答失败。
     *
     * @param query 用户问题
     * @param topK  召回条数
     * @return 召回结果；异常时返回空列表
     */
    private List<Document> safeVectorSearch(String query, int topK) {
        try {
            return searchByVector(query, topK);
        } catch (Exception e) {
            log.warn("向量检索失败，本次降级为空结果: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 截取前 {@code limit} 条。
     *
     * @param list  原列表
     * @param limit 上限；<=0 时表示不限制
     * @return 截取后的列表（长度未超限时返回原列表引用）
     */
    private static <T> List<T> truncate(List<T> list, int limit) {
        if (limit <= 0 || list.size() <= limit) {
            return list;
        }
        return new ArrayList<>(list.subList(0, limit));
    }

    /**
     * 压缩文本为单行摘要，便于调试接口直接查看。
     *
     * @param text 原文
     * @param max  最大长度，超出部分以 {@code ...} 结尾
     * @return 摘要文本；入参为 null 时返回空串
     */
    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() > max ? flat.substring(0, max) + "..." : flat;
    }

    /** 召回路线名称，用于记录各路名次 */
    public static final class VectorRoute {

        /** 第一路：向量语义召回 */
        public static final String VECTOR = "vector";

        /** 第二路：PG 原生全文检索召回 */
        public static final String KEYWORD = "keyword";

        private VectorRoute() {
        }
    }

    /**
     * 融合过程中的中间结果。RRF 分数要在多路之间累加，因此用可变类。
     */
    public static final class FusionItem {

        private final String key;
        private final Document document;
        private final Map<String, Integer> ranks = new LinkedHashMap<>();
        private double score;

        /**
         * @param key      融合主键，通常是文档 id
         * @param document 首次被召回时的文档实例
         */
        FusionItem(String key, Document document) {
            this.key = key;
            this.document = document;
        }

        /**
         * 累加某一路对该文档的 RRF 贡献。
         *
         * @param rrfScore 该路的 RRF 贡献，即 {@code 1 / (k + rank)}
         * @param route    路线标识，见 {@link VectorRoute}
         * @param rank     该路中的名次，从 1 开始
         */
        void add(double rrfScore, String route, int rank) {
            this.score += rrfScore;
            this.ranks.put(route, rank);
        }

        /** @return 融合主键 */
        public String key() {
            return key;
        }

        /** @return 文档实例 */
        public Document document() {
            return document;
        }

        /** @return RRF 累计得分 */
        public double score() {
            return score;
        }

        /** @return 各路名次，形如 {@code {vector=2, keyword=1}} */
        public Map<String, Integer> ranks() {
            return ranks;
        }
    }

    /**
     * 混合检索结果。
     *
     * @param documents        最终送入大模型的上下文文档（已按 RRF 得分降序、取 Top-N）
     * @param vectorDocs       向量路原始召回
     * @param keywordDocs      关键词路原始召回
     * @param trace            融合明细，用于管理接口排查（RRF 得分、各路名次）
     * @param keywordAvailable 关键词路是否可用
     * @param degraded         是否发生了降级（融合未真正生效）
     */
    public record HybridResult(List<Document> documents,
                               List<Document> vectorDocs,
                               List<Document> keywordDocs,
                               List<Map<String, Object>> trace,
                               boolean keywordAvailable,
                               boolean degraded) {
    }
}
