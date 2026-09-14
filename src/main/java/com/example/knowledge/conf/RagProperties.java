package com.example.knowledge.conf;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 相关可配置项，集中管理，避免参数散落在代码里。
 */
@Data
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

    /** 管理接口访问令牌，为空则开发模式不鉴权 */
    private String authToken = "";

    /** 是否在应用启动时加载知识库 */
    private boolean loadOnStartup = true;

    /**
     * 启动时从向量表回填"已加载来源"，实现持久化向量库下的增量加载。
     * <p>关闭后每次启动都会按来源重新切分并重新向量化全部文档
     * （不会产生重复数据，但会消耗 Embedding 配额）。</p>
     */
    private boolean syncLoadedSources = true;

    /** 检索召回条数 */
    private int topK = 4;

    /** 相似度阈值，低于该值的结果被过滤 */
    private double similarityThreshold = 0.5;

    /** 对话记忆保留的最大消息数 */
    private int maxMessages = 10;

    /**
     * 旧版 SimpleVectorStore 的 JSON 持久化文件路径。
     * <p>pgvector 模式下向量已落库，该文件仅作为一次性迁移的数据源；
     * simple 模式下它仍是向量库的持久化文件。</p>
     */
    private String vectorStorePath = "./data/vector-store.json";

    /** 对话记忆持久化文件路径 */
    private String chatMemoryPath = "./data/chat-memory.json";

    /** 系统提示词 */
    private String systemPrompt = "你是一名企业知识库助手，请依据检索到的知识片段回答问题。";

    /** 文本切分参数 */
    private Splitter splitter = new Splitter();

    /** 知识文档清单，按扩展名自动选择解析器 */
    private List<Resource> documents = new ArrayList<>();

    /** 知识文档目录：该目录下所有受支持格式的文件会被自动发现并加载（含上传的文件） */
    private String docDir = "./data/docs";

    /** 一次性数据迁移：SimpleVectorStore JSON -> PgVector */
    private Migrate migrate = new Migrate();

    /** 混合检索（PG 原生全文检索 + 向量检索 + RRF 融合） */
    private Hybrid hybrid = new Hybrid();

    @Data
    public static class Splitter {
        private int chunkSize = 800;
        private int minChunkSizeChars = 350;
        private int minChunkLengthToEmbed = 5;
        private int maxNumChunks = 10000;
        private boolean keepSeparator = true;
    }

    /**
     * 旧向量库数据迁移配置。
     */
    @Data
    public static class Migrate {
        /** 是否在应用启动时自动执行迁移 */
        private boolean enabled = false;
        /** 目标向量表已有数据时是否跳过，避免重复导入 */
        private boolean skipIfNotEmpty = true;
        /** 迁移数据源文件，留空则复用 {@link #vectorStorePath} */
        private String sourceFile = "";
        /** 是否保留源 JSON 文件（false 表示迁移成功后重命名为 .bak 备份） */
        private boolean keepSourceFile = true;
    }

    /**
     * 混合检索配置：向量路 + 关键词路双路召回，再用 RRF 融合。
     *
     * <p>关闭后会退化成纯向量检索（等同于改造前的 QuestionAnswerAdvisor 行为）。</p>
     */
    @Data
    public static class Hybrid {
        /** 是否启用混合检索 */
        private boolean enabled = true;

        /** 向量路召回条数（RRF 会重新排序，所以召回阶段可以放宽） */
        private int vectorTopK = 10;

        /** 关键词路（PG 全文检索）召回条数 */
        private int keywordTopK = 10;

        /** RRF 融合后最终送入大模型的上下文条数 */
        private int finalTopK = 5;

        /**
         * RRF 平滑常数 k，越大则低排名结果的权重差异越小（常用取值 60）。
         * 公式：score(d) = Σ 1 / (k + rank(d))，rank 从 1 开始。
         */
        private int rrfK = 60;

        /**
         * 文本搜索配置（{@code to_tsvector} 的第一个参数）。
         * <p>PG 无内置中文分词，默认 simple；若装了 zhparser/pg_jieba，改成对应配置名即可。</p>
         */
        private String ftsConfig = "simple";

        /**
         * 把 {@code plainto_tsquery} 的 AND 语义改写为 OR 语义。
         * <p>检索场景看重召回，默认的多词 AND 过严（漏召回），改成 OR 后命中率明显提升。</p>
         */
        private boolean queryOrExpansion = true;

        /**
         * 中文兜底：额外用 {@code ILIKE '%query%'} 做子串召回。
         * <p>single 分词器无法切分中文，不开这项中文问题在关键词路基本召不回
         * （实测 0 行），双路会退化成单路。</p>
         */
        private boolean substringFallback = true;

        /** 是否把查询串再切成词/短语分别做子串匹配（提升中文长句召回） */
        private boolean substringTokenSplit = true;

        /**
         * 向量路的相似度阈值。<=0 表示召回阶段不过滤（交由 RRF 统一排序）。
         * <p>混合检索下提前过滤会丢掉本可通过关键词路补齐的结果，因此默认放宽。</p>
         */
        private double vectorSimilarityThreshold = 0.0;
    }
}
