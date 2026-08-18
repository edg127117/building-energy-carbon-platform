package com.platform.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
/** 为版本化设备接入接口声明 JWT OpenAPI 安全方案。 */
public class OnboardingOpenApiConfig {
    @Bean
    public OpenAPI deviceOnboardingOpenApi() {
        return new OpenAPI()
                .info(new Info().title("IoT Platform API").version("v1"))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
