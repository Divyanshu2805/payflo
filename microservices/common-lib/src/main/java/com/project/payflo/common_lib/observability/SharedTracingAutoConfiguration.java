package com.project.payflo.common_lib.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import zipkin2.reporter.BytesMessageSender;
import zipkin2.reporter.urlconnection.URLConnectionSender;

// Sends spans to Zipkin over plain HttpURLConnection, so tracing doesn't depend on which HTTP
// client (RestClient, WebClient, JDK HttpClient) a given service happens to have on its classpath.
@AutoConfiguration
public class SharedTracingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(BytesMessageSender.class)
    public BytesMessageSender zipkinSender(
            @Value("${management.zipkin.tracing.endpoint:http://localhost:9411/api/v2/spans}") String endpoint) {
        return URLConnectionSender.create(endpoint);
    }
}
