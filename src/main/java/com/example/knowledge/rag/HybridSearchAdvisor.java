package com.example.knowledge.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把「混合检索」接入问答链路的 Advisor。
 *
 * <p>Spring AI 内置的 {@code QuestionAnswerAdvisor} 只会调用
 * {@code vectorStore.similaritySearch(...)} 做单路语义召回，
 * 无法塞入第二路（PG 原生全文检索）。因此这里实现一个同构的 Advisor：
 * 在请求进入大模型之前执行混合检索，把 RRF 融合后的文档拼进用户消息，
 * 并保持与 {@code QuestionAnswerAdvisor} 一致的上下文约定
 * （{@code qa_retrieved_documents}），便于观测与其它 Advisor 复用。</p>
 *
 * <p>未启用混合检索或关键词路不可用时，自动降级为纯向量检索，行为与改造前一致。</p>
 */
@Slf4j
public class HybridSearchAdvisor implements BaseAdvisor {

    /** 与 QuestionAnswerAdvisor 保持同名，便于共用观测/后处理逻辑 */
    public static final String RETRIEVED_DOCUMENTS = "qa_retrieved_documents";

    /** 未命中时的占位上下文，避免 LLM 拿到空白上下文后自由发挥 */
    private static final String EMPTY_CONTEXT = "（本次检索没有命中任何知识片段）";

    private static final PromptTemplate DEFAULT_PROMPT_TEMPLATE = new PromptTemplate("""
            以下是从知识库中检索到的内容：
            ---------------------
            {question_answer_context}
            ---------------------
            请严格依据上述内容回答问题，遵循以下规则：
            1. 如果上述内容不足以回答，请直接回复"知识库中未找到相关内容"，不要编造；
            2. 不要使用上述内容之外的先验知识进行推测。

            问题：{query}
            回答：""");

    private final HybridSearchService hybridSearchService;
    private final PromptTemplate promptTemplate;
    private final int order;

    /**
     * 使用默认提示词模板与默认执行次序构造。
     *
     * @param hybridSearchService 混合检索服务，负责双路召回与 RRF 融合
     */
    public HybridSearchAdvisor(HybridSearchService hybridSearchService) {
        this(hybridSearchService, DEFAULT_PROMPT_TEMPLATE);
    }

    /**
     * 指定提示词模板，执行次序用默认值（排在会话记忆 Advisor 之后）。
     *
     * @param hybridSearchService 混合检索服务
     * @param promptTemplate      自定义提示词模板，需含
     *                            {@code {query}} 与 {@code {question_answer_context}} 两个占位符
     */
    public HybridSearchAdvisor(HybridSearchService hybridSearchService, PromptTemplate promptTemplate) {
        this(hybridSearchService, promptTemplate, Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 1);
    }

    /**
     * 全参数构造。
     *
     * @param hybridSearchService 混合检索服务
     * @param promptTemplate      提示词模板，传 null 时回退到默认模板
     * @param order               执行次序；值越大越晚执行，需要排在会话记忆之后
     *                            才能拿到已补齐的多轮上下文
     */
    public HybridSearchAdvisor(HybridSearchService hybridSearchService,
                               PromptTemplate promptTemplate,
                               int order) {
        this.hybridSearchService = hybridSearchService;
        this.promptTemplate = promptTemplate == null ? DEFAULT_PROMPT_TEMPLATE : promptTemplate;
        this.order = order;
    }

    /**
     * 请求进入大模型之前的钩子：执行混合检索，并把融合后的文档拼进用户消息。
     *
     * <p>同时把召回结果写入上下文 {@value #RETRIEVED_DOCUMENTS}，
     * 便于后续 Advisor 或观测组件复用。</p>
     *
     * @param request 原始请求
     * @param chain   责任链
     * @return 携带知识上下文的新请求
     */
    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String userText = resolveUserText(request);
        List<Document> documents = StringUtils.hasText(userText)
                ? hybridSearchService.search(userText).documents()
                : List.of();

        Map<String, Object> context = new HashMap<>(request.context());
        context.put(RETRIEVED_DOCUMENTS, documents);

        String rendered = promptTemplate.render(Map.of(
                "query", userText == null ? "" : userText,
                "question_answer_context", buildContext(documents)));

        return request.mutate()
                .prompt(request.prompt().augmentUserMessage(rendered))
                .context(context)
                .build();
    }

    /**
     * 大模型返回之后的钩子。本项目不需要对回答做二次处理，原样透传。
     *
     * @param response 模型响应
     * @param chain    责任链
     * @return 未修改的响应
     */
    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    /**
     * 执行次序。默认排在会话记忆 Advisor 之后，确保检索时上下文已完整。
     *
     * @return 次序值
     */
    @Override
    public int getOrder() {
        return order;
    }

    /**
     * Advisor 名称，用于日志与责任链排查。
     *
     * @return 固定名称 {@code HybridSearchAdvisor}
     */
    @Override
    public String getName() {
        return "HybridSearchAdvisor";
    }

    /**
     * 取用户消息原文作为检索输入。
     * <p>刻意取原始提问而非"记忆补全后的文本"，避免历史轮次里的词污染本次召回。</p>
     *
     * @param request 请求
     * @return 用户消息文本；不存在时返回空串
     */
    private String resolveUserText(ChatClientRequest request) {
        UserMessage userMessage = request.prompt().getUserMessage();
        if (userMessage == null) {
            return "";
        }
        String text = userMessage.getText();
        return text == null ? "" : text;
    }

    /**
     * 把文档列表渲染成提示词里的上下文块。
     *
     * <p>每段都带上来源文件名，让模型能在答案里标注出处；
     * 未命中时返回 {@value #EMPTY_CONTEXT} 占位，避免模型拿到空上下文后自由发挥。</p>
     *
     * @param documents RRF 融合后的文档，按得分降序
     * @return 拼接后的上下文文本
     */
    private String buildContext(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return EMPTY_CONTEXT;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            if (sb.length() > 0) {
                sb.append(System.lineSeparator());
            }
            String source = String.valueOf(doc.getMetadata().getOrDefault("source", "unknown"));
            sb.append("[片段 ").append(i + 1).append("] 来源：").append(source)
                    .append(System.lineSeparator())
                    .append(doc.getText() == null ? "" : doc.getText());
        }
        return sb.toString();
    }
}
