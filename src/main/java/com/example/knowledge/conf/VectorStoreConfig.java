package com.example.knowledge.conf;

import com.example.knowledge.rag.FileChatMemoryRepository;
import com.example.knowledge.rag.HybridSearchAdvisor;
import com.example.knowledge.rag.HybridSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.file.Path;

/**
 * 核心组件装配：向量库（含持久化）、文本切分器、对话记忆、ChatClient。
 *
 * <p>向量库由 Spring AI 自动装配，通过 {@code spring.ai.vectorstore.type} 切换实现：</p>
 * <ul>
 *   <li>{@code pgvector}（默认）—— PostgreSQL + pgvector 扩展，数据落库、重启不丢；
 *       由 {@code PgVectorStoreAutoConfiguration} 自动创建，无需手写 Bean。</li>
 *   <li>{@code simple} —— 内存向量库 + JSON 文件持久化，用于离线/应急/单元测试，
 *       在本类中按条件装配。</li>
 *   <li>{@code none} —— 不装配任何向量库（测试时配合自定义 Bean 使用）。</li>
 * </ul>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class VectorStoreConfig {

    private final RagProperties props;

    /**
     * 回退用的内存向量库 + 本地文件持久化：仅在 {@code spring.ai.vectorstore.type=simple} 时生效。
     * <p>此时 PgVectorStore 的自动装配会因 {@code havingValue="pgvector"} 条件不满足而自动让位，
     * 因此容器内始终只有一个 {@link VectorStore}。</p>
     */
    @Bean
    @ConditionalOnProperty(name = "spring.ai.vectorstore.type", havingValue = "simple")
    public VectorStore simpleVectorStore(EmbeddingModel embeddingModel) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        File file = Path.of(props.getVectorStorePath()).toAbsolutePath().normalize().toFile();
        if (file.exists() && file.length() > 0) {
            try {
                store.load(file);
                log.info("simple 模式：已从 {} 恢复向量库", file.getAbsolutePath());
            } catch (Exception e) {
                log.warn("恢复向量库失败，将使用空向量库: {}", e.getMessage());
            }
        } else {
            log.info("simple 模式：未发现本地向量库文件 {}，将使用空向量库", file.getAbsolutePath());
        }
        return store;
    }

    /**
     * 混合检索 Advisor：向量路 + PG 原生全文检索双路召回，再用 RRF 融合取 Top-N 作为上下文。
     * 未启用或关键词路不可用时自动降级为纯向量检索。
     */
    @Bean
    public HybridSearchAdvisor hybridSearchAdvisor(HybridSearchService hybridSearchService) {
        return new HybridSearchAdvisor(hybridSearchService);
    }

    /**
     * 文本切分器。参数取自 {@code app.rag.splitter.*}，
     * 切分粒度直接决定召回片段的完整性，需在检索效果与上下文长度之间权衡。
     *
     * @return 基于 token 的文本切分器
     */
    @Bean
    public DocumentTransformer textSplitter() {
        RagProperties.Splitter s = props.getSplitter();
        return TokenTextSplitter.builder()
                .withChunkSize(s.getChunkSize())
                .withMinChunkSizeChars(s.getMinChunkSizeChars())
                .withMinChunkLengthToEmbed(s.getMinChunkLengthToEmbed())
                .withMaxNumChunks(s.getMaxNumChunks())
                .withKeepSeparator(s.isKeepSeparator())
                .build();
    }

    /**
     * 对话记忆：文件持久化，重启后多轮上下文不丢失。
     */
    @Bean
    public ChatMemory chatMemory() {
        ChatMemoryRepository repository = new FileChatMemoryRepository(props.getChatMemoryPath());
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(props.getMaxMessages())
                .build();
    }

    /**
     * 全局 ChatClient：默认挂上会话记忆 Advisor 与系统提示词。
     *
     * <p>检索 Advisor 不在这里注册，而是由 {@link com.example.knowledge.controller.RagController}
     * 按请求动态选择——混合检索与纯向量检索需要可切换。</p>
     *
     * @param builder    框架提供的构造器，已预置模型与观测配置
     * @param chatMemory 会话记忆，用于多轮对话
     * @return 配置好的 ChatClient
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory) {
        ChatClient.Builder b = builder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build());
        if (StringUtils.hasText(props.getSystemPrompt())) {
            b = b.defaultSystem(props.getSystemPrompt());
        }
        return b.build();
    }
}
