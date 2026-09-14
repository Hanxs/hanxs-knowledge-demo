package com.example.knowledge.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动期执行一次性向量数据迁移，仅在 {@code app.rag.migrate.enabled=true} 时装配。
 *
 * <pre>
 * # 方式一：命令行参数（推荐，迁移完即失效）
 * ./gradlew bootRun --args='--app.rag.migrate.enabled=true'
 * java -jar app.jar --app.rag.migrate.enabled=true
 *
 * # 方式二：环境变量
 * RAG_MIGRATE_ENABLED=true ./gradlew bootRun
 * </pre>
 *
 * <p>迁移失败不会阻断应用启动，可查看日志或调用
 * {@code POST /rag/admin/knowledge/migrate} 重试。</p>
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.rag.migrate.enabled", havingValue = "true")
public class VectorStoreMigrationRunner implements ApplicationRunner {

    private final VectorStoreMigrationService migrationService;

    /**
     * 启动期触发一次性迁移。
     *
     * <p>捕获全部异常：迁移属于一次性运维动作，失败也只记录日志，
     * 不能因此让应用起不来。修复后可通过命令行重跑或调用管理接口重试。</p>
     *
     * @param args 启动参数，本实现不解析
     */
    @Override
    public void run(ApplicationArguments args) {
        log.info("检测到 app.rag.migrate.enabled=true，开始执行 SimpleVectorStore -> PgVector 一次性迁移");
        try {
            log.info("迁移结果: {}", migrationService.migrate());
        } catch (Exception e) {
            log.error("向量数据迁移失败（不影响应用启动，可调用 POST /rag/admin/knowledge/migrate 重试）: {}",
                    e.getMessage());
            log.debug("迁移失败详情", e);
        }
    }
}
