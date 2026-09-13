package com.example.knowledge.controller;

import com.example.knowledge.common.ApiResponse;
import com.example.knowledge.conf.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
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
 */
@RestController
@RequestMapping("/rag")
@RequiredArgsConstructor
public class RagController {

    public static final String DEFAULT_SESSION_ID = "default-001";

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final RagProperties props;

    /**
     * 标准 RAG 问答：/rag/ask?msg=你的问题&sessionId=可选会话ID
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
     * 流式 RAG 问答（SSE）：/rag/ask/stream?msg=你的问题&sessionId=可选会话ID
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

    private QuestionAnswerAdvisor qaAdvisor() {
        SearchRequest searchRequest = SearchRequest.builder()
                .topK(props.getTopK())
                .similarityThreshold(props.getSimilarityThreshold())
                .build();
        return QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(searchRequest)
                .build();
    }

    private String resolveSession(String sessionId) {
        return StringUtils.hasText(sessionId) ? sessionId : DEFAULT_SESSION_ID;
    }
}
