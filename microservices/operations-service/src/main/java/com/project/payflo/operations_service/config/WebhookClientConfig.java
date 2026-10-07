package com.project.payflo.operations_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class WebhookClientConfig {

    /**
     * The client webhooks are sent with. It is the JDK's {@link HttpClient} for two reasons:
     * <ul>
     *   <li><b>It reuses connections.</b> The older {@code HttpURLConnection} keeps five idle connections per host, so
     *       at a few hundred deliveries a second nearly every one opened (and left in TIME_WAIT) a new TCP connection,
     *       and the machine ran out of ephemeral ports ("Address already in use") for deliveries and every other
     *       client on it. This one keeps connections alive and shares them.</li>
     *   <li><b>It does not follow redirects.</b> The target URL is validated before each delivery (private and metadata
     *       addresses are refused); a redirect would be followed to wherever the merchant's server pointed, past that
     *       check. A {@code 3xx} is a failed attempt, retried on the usual schedule.</li>
     * </ul>
     * HTTP/1.1 only: the client would otherwise offer an h2c upgrade on every cleartext request.
     */
    @Bean
    public RestClient webhookRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));

        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }

}
