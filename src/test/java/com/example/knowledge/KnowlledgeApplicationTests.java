package com.example.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 上下文加载测试：使用占位密钥并关闭启动期知识库加载，避免测试依赖真实模型服务。
 *
 * <p>向量库切换为 {@code simple}：PgVector 的自动装配条件为
 * {@code spring.ai.vectorstore.type=pgvector}，改为 simple 后自动让位，
 * 测试无需启动 PostgreSQL，同时顺带验证了向量库可插拔切换。</p>
 */
@SpringBootTest(properties = {
		"app.rag.load-on-startup=false",
		"spring.ai.openai.api-key=test-key",
		"spring.ai.vectorstore.type=simple"
})
class KnowlledgeApplicationTests {

	@Test
	void contextLoads() {
	}
}
