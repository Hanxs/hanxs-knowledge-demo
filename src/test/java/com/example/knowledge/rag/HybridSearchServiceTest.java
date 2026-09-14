package com.example.knowledge.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RRF 融合逻辑单测（纯内存，不依赖 PG / Embedding 模型）。
 */
class HybridSearchServiceTest {

    private static final int K = 60;

    private final HybridSearchService service = new HybridSearchService(null, null, null);

    /**
     * 构造测试用文档。
     *
     * @param id 文档主键，同时作为融合主键
     * @return 仅含 id 与正文的文档
     */
    private static Document doc(String id) {
        return Document.builder().id(id).text("内容-" + id).build();
    }

    /** 公式校验：score = Σ 1/(k+rank)，rank 从 1 开始 */
    @Test
    void fuseCalculatesReciprocalRankScore() {
        List<Document> vector = List.of(doc("A"), doc("B"));
        List<Document> keyword = List.of(doc("B"));

        List<HybridSearchService.FusionItem> fused = service.fuse(vector, keyword, K);

        Map<String, Double> scores = fused.stream()
                .collect(java.util.stream.Collectors.toMap(HybridSearchService.FusionItem::key,
                        HybridSearchService.FusionItem::score));
        // B：向量第 2 + 关键词第 1
        assertEquals(1.0 / (K + 2) + 1.0 / (K + 1), scores.get("B"), 1e-9);
        // A：仅向量第 1
        assertEquals(1.0 / (K + 1), scores.get("A"), 1e-9);
    }

    /** 两条路都命中的文档应排到最前 */
    @Test
    void fusePromotesDocumentsHitByBothRoutes() {
        List<Document> vector = List.of(doc("A"), doc("B"), doc("C"));
        List<Document> keyword = List.of(doc("C"));

        List<HybridSearchService.FusionItem> fused = service.fuse(vector, keyword, K);

        assertEquals("C", fused.get(0).key(), "双路命中应排第一");
        assertEquals(Map.of(HybridSearchService.VectorRoute.VECTOR, 3,
                        HybridSearchService.VectorRoute.KEYWORD, 1),
                new java.util.LinkedHashMap<>(fused.get(0).ranks()));
    }

    /** 关键词路名次的提升，可以把向量路排名靠后的文档反超到前面 */
    @Test
    void keywordRouteCanOvertakeVectorRoute() {
        List<Document> vector = List.of(doc("A"), doc("B"));
        List<Document> keyword = List.of(doc("B"), doc("C"));

        List<HybridSearchService.FusionItem> fused = service.fuse(vector, keyword, K);

        assertEquals("B", fused.get(0).key());
        // C 只被关键词路召回（第 2），A 只被向量路召回（第 1）：两者得分互有胜负，但都低于双路命中的 B
        assertTrue(fused.get(0).score() > fused.get(1).score());
        assertEquals(3, fused.size());
    }

    /** 单路为空时不应报错，结果等于另一条路 */
    @Test
    void fuseHandlesEmptyRoute() {
        List<Document> vector = List.of(doc("A"));
        List<HybridSearchService.FusionItem> fused = service.fuse(vector, List.of(), K);

        assertEquals(1, fused.size());
        assertEquals("A", fused.get(0).key());
    }
}
