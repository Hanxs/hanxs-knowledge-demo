package com.example.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 上下文加载测试：使用占位密钥并关闭启动期知识库加载，避免测试依赖真实模型服务。
 */
@SpringBootTest(properties = {
		"app.rag.load-on-startup=false",
		"spring.ai.openai.api-key=test-key"
})
class KnowlledgeApplicationTests {

	@Test
	void contextLoads() {
	}
}
