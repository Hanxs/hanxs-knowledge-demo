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

    /** 检索召回条数 */
    private int topK = 4;

    /** 相似度阈值，低于该值的结果被过滤 */
    private double similarityThreshold = 0.5;

    /** 对话记忆保留的最大消息数 */
    private int maxMessages = 10;

    /** 向量库持久化文件路径 */
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

    @Data
    public static class Splitter {
        private int chunkSize = 800;
        private int minChunkSizeChars = 350;
        private int minChunkLengthToEmbed = 5;
        private int maxNumChunks = 10000;
        private boolean keepSeparator = true;
    }
}
