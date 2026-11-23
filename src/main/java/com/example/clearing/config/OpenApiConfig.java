package com.example.clearing.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI clearingOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("医保清算韧性服务 API")
                        .version("1.0.0")
                        .description("任务登记、失败判定、依赖状态图、幂等账本、预算分配（依赖本地令牌桶 + 协调器配额证据）、"
                                + "调度、风暴观测与恢复解释。写操作需 admin 账号，查询可用 viewer 账号。"))
                .components(new Components().addSecuritySchemes("basicAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")))
                .addSecurityItem(new SecurityRequirement().addList("basicAuth"));
    }
}
