package com.example.knowledge.rag;

import com.example.knowledge.conf.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 启动期加载知识库：把原来"手动调接口触发"改为应用启动即完成，失败不影响应用启动。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseInitializer implements ApplicationRunner {

    private final KnowledgeBaseLoader loader;
    private final RagProperties props;

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isLoadOnStartup()) {
            log.info("app.rag.load-on-startup=false，跳过启动期知识库加载");
            return;
        }
        if (!StringUtils.hasText(apiKey)) {
            log.warn("未检测到 spring.ai.openai.api-key（环境变量 DASHSCOPE_API_KEY），已跳过启动期知识库加载。");
            log.warn("配置密钥后，可通过 POST /rag/admin/knowledge/load?force=true 手动加载知识库。");
            return;
        }
        try {
            int total = loader.loadAll();
            log.info("启动期知识库加载完成，本次入库 {} 个片段", total);
        } catch (Exception e) {
            log.error("启动期知识库加载失败（不影响应用启动，可通过 POST /rag/admin/knowledge/load?force=true 重试）: {}",
                    e.getMessage());
            log.debug("知识库加载失败详情", e);
        }
    }
}
