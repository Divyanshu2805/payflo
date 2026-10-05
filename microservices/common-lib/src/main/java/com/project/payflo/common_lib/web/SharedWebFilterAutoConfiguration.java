package com.project.payflo.common_lib.web;

import com.project.payflo.common_lib.context.MerchantContext;
import jakarta.servlet.Filter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.filter.RequestContextFilter;

@AutoConfiguration
public class SharedWebFilterAutoConfiguration {

    @Bean
    @ConditionalOnProperty(name = "app.security.trust-inbound-headers", havingValue = "true", matchIfMissing = true)
    public FilterRegistrationBean<Filter> merchantContextRegistration(MerchantContext merchantContext) {
        FilterRegistrationBean<Filter> registration =
                new FilterRegistrationBean<>(new MerchantContextFilter(merchantContext));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE+1);
        registration.addUrlPatterns("/*");
        return registration;
    }

    // Every /internal/** request must carry the token the services share (INTERNAL_API_TOKEN).
    @Bean
    public FilterRegistrationBean<Filter> internalApiAuthRegistration(
            @Value("${internal.api-token:dev-internal-api-token-change-me}") String token) {
        FilterRegistrationBean<Filter> registration =
                new FilterRegistrationBean<>(new InternalApiAuthFilter(token));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE+1);
        registration.addUrlPatterns("/internal/*");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<Filter> requestContextFilterRegistration() {
        FilterRegistrationBean<Filter> registration =
                new FilterRegistrationBean<>(new RequestContextFilter());

        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return  registration;
    }
}
