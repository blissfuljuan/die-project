package com.blissfuljuan.dieapi.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI dieOpenApi() {
        return new OpenAPI().info(new Info()
            .title("Document-Implementation Evaluator API")
            .version("v1")
            .description("AI output is advisory; instructors hold final authority."));
    }
}
