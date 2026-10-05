package com.project.payflo.operations_service.config;

import com.project.payflo.common_lib.web.InternalApiAuthFilter;
import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Every Feign call here goes to another service's /internal/** API, which requires the shared token.
@Configuration
public class InternalAuthFeignConfig {

    @Bean
    public RequestInterceptor internalTokenInterceptor(
            @Value("${internal.api-token:dev-internal-api-token-change-me}") String token) {
        return template -> template.header(InternalApiAuthFilter.TOKEN_HEADER, token);
    }
}
