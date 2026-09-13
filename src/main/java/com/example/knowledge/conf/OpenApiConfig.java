package com.example.knowledge.conf;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger 元信息。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI knowledgeOpenApi() {
        return new OpenAPI().info(new Info()
                .title("hanxs-knowledge-demo API")
                .version("v1")
                .description("基于 Spring AI 的 RAG 知识库问答示例"));
    }
}
