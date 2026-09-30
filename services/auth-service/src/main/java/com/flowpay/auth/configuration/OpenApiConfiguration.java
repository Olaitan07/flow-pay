package com.flowpay.auth.configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI authOpenApi() {
        return new OpenAPI()
                .info(new Info().title("FlowPay Auth Service").version("v1").description("Login, tokens, logout, password reset and two-step verification."))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                                .description("Access token from POST /api/v1/auth/login (or /login/verify)."))
                        .addSecuritySchemes("internalApiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER)
                                .name("X-Internal-Api-Key")
                                .description("Shared secret for service-to-service calls (INTERNAL_API_KEY in .env).")));
    }
}
