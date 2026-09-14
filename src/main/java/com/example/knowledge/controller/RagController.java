package com.example.knowledge.controller;

import com.example.knowledge.common.ApiResponse;
import com.example.knowledge.conf.RagProperties;
import com.example.knowledge.rag.HybridSearchAdvisor;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * RAG 问答接口：普通（阻塞式）与流式两种返回方式。
 *
 * <p>上下文召回走 {@link HybridSearchAdvisor}（向量路 + PG 原生全文检索双路召回 + RRF 融合）；
 * 未启用混合检索时在 Advisor 内部自动降级为纯向量检索。</p>
 */
@RestController
@RequestMapping("/rag")
@RequiredArgsConstructor
public class RagController {

    public static final String DEFAULT_SESSION_ID = "default-001";

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final RagProperties props;
    private final HybridSearchAdvisor hybridSearchAdvisor;

    /**
     * 标准 RAG 问答（阻塞式）。
     *
     * <p>链路：取出用户问题 → 混合检索（双路召回 + RRF）→ 拼进提示词 → 调用大模型。</p>
     *
     * @param msg       用户问题
     * @param sessionId 会话 ID，用于多轮记忆；缺省时用 {@link #DEFAULT_SESSION_ID}
     * @return 模型回答
     */
    @GetMapping("/ask")
    public ApiResponse<String> ask(@RequestParam String msg,
                                   @RequestParam(required = false) String sessionId) {
        String answer = chatClient.prompt()
                .user(msg)
                .advisors(qaAdvisor())
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, resolveSession(sessionId)))
                .call()
                .content();
        return ApiResponse.ok(answer);
    }

    /**
     * 流式 RAG 问答（SSE）。检索阶段与 {@link #ask} 完全一致，只是把生成结果按 token 推送。
     *
     * @param msg       用户问题
     * @param sessionId 会话 ID，用于多轮记忆；缺省时用 {@link #DEFAULT_SESSION_ID}
     * @return 逐段返回的文本流
     */
    @GetMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> askStream(@RequestParam String msg,
                                  @RequestParam(required = false) String sessionId) {
        return chatClient.prompt()
                .user(msg)
                .advisors(qaAdvisor())
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, resolveSession(sessionId)))
                .stream()
                .content();
    }

    /**
     * 取本次请求要用的检索 Advisor。
     * <p>默认走混合检索；{@code app.rag.hybrid.enabled=false} 时退回官方
     * QuestionAnswerAdvisor（纯向量检索），便于对照效果。</p>
     */
    private Advisor qaAdvisor() {
        if (props.getHybrid().isEnabled()) {
            return hybridSearchAdvisor;
        }
        SearchRequest searchRequest = SearchRequest.builder()
                .topK(props.getTopK())
                .similarityThreshold(props.getSimilarityThreshold())
                .build();
        return QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(searchRequest)
                .build();
    }

    /**
     * 归一会话 ID：未传或空白时回落到默认会话，保证同一用户的多轮对话能连上。
     *
     * @param sessionId 入参会话 ID，可为空
     * @return 实际使用的会话 ID
     */
    private String resolveSession(String sessionId) {
        return StringUtils.hasText(sessionId) ? sessionId : DEFAULT_SESSION_ID;
    }
}
