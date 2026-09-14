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
    private final VectorStoreMigrationService vectorStoreService;

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    /**
     * 启动期钩子：回填已加载来源，再做增量入库。
     *
     * <p>三个前置条件任一不满足就跳过：未开启 {@code load-on-startup}、未配置 API Key。
     * 入库失败也只记录日志、不抛异常——知识库加载失败不应阻断应用启动，
     * 事后可用 {@code POST /rag/admin/knowledge/load?force=true} 重试。</p>
     *
     * @param args 启动参数，本实现不解析
     */
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
        syncLoadedSources();
        try {
            int total = loader.loadAll();
            log.info("启动期知识库加载完成，本次入库 {} 个片段", total);
        } catch (Exception e) {
            log.error("启动期知识库加载失败（不影响应用启动，可通过 POST /rag/admin/knowledge/load?force=true 重试）: {}",
                    e.getMessage());
            log.debug("知识库加载失败详情", e);
        }
    }

    /**
     * 用向量表中已有的来源回填"已加载"状态。
     * <p>PgVector 是持久化存储，重启后向量仍在，但内存态的已加载集合会清空。
     * 不回填就会对全部文档重新切分 + 重新向量化；回填后增量加载得以继续生效，
     * 只有新增/变更的文件才会调用 Embedding 接口。</p>
     */
    private void syncLoadedSources() {
        if (!props.isSyncLoadedSources()) {
            return;
        }
        try {
            var sources = vectorStoreService.distinctSources();
            if (!sources.isEmpty()) {
                loader.primeLoadedSources(sources);
                log.info("向量库已存在 {} 个来源，登记为已加载（增量跳过，避免重复向量化）：{}",
                        sources.size(), sources);
            }
        } catch (Exception e) {
            log.warn("回填已加载来源失败，本次将按全量加载处理: {}", e.getMessage());
        }
    }
}
