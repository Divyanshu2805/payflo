package com.project.payflo.api_gateway_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// The gateway proxies to downstream services with Apache HttpClient 5, whose connection pool
// defaults to 5 connections per route. Under load every request to a busy service then queues in
// the gateway waiting for one of those 5 connections (measured: ~350ms added at 100 concurrent
// users, while payment-service itself answered in ~25ms). Size the pool for real concurrency.
@Configuration
public class ProxyHttpClientConfig {

    @Bean
    public ClientHttpRequestFactoryBuilder<?> clientHttpRequestFactoryBuilder(
            @Value("${app.gateway.proxy.max-connections-per-route:200}") int maxPerRoute,
            @Value("${app.gateway.proxy.max-connections-total:1000}") int maxTotal) {
        return ClientHttpRequestFactoryBuilder.httpComponents()
                .withConnectionManagerCustomizer(pool -> pool
                        .setMaxConnPerRoute(maxPerRoute)
                        .setMaxConnTotal(maxTotal));
    }
}
