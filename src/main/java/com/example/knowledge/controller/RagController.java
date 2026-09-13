package com.example.knowledge.controller;

import com.example.knowledge.rag.KnowledgeBaseLoader;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/rag")
public class RagController {

    @Resource
    private ChatClient chatClient;

    @Resource
    private KnowledgeBaseLoader knowledgeBaseLoader;

    @Resource
    private VectorStore vectorStore;

    /**
     * 加载 PDF 知识库
     * 访问：http://localhost:8080/rag/loadPdf
     */
    @GetMapping("/loadPdf")
    public String loadPdfKnowledgeBase() {
        knowledgeBaseLoader.loadPdfToVectorStore();
        return "PDF知识库加载成功";
    }

    /**
     * 加载 TXT 知识库
     * 访问：http://localhost:8080/rag/loadTxt
     */
    @GetMapping("/loadTxt")
    public String loadTxtKnowledgeBase() {
        knowledgeBaseLoader.loadTxtToVectorStore();
        return "TXT知识库加载成功";
    }

    /**
     * 标准RAG智能问答接口
     * 访问：http://localhost:8080/rag/ask?msg=你的问题
     */
    @GetMapping("/ask")
    public String ask(@RequestParam String msg) {
        return chatClient.prompt()
                .user("简单介绍下" + msg)
                .advisors(QuestionAnswerAdvisor.builder(vectorStore).build())
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, "default-001"))
                .call()
                .content();
    }
}
