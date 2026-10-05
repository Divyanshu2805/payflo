package com.project.payflo.api_gateway_service.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.security")
public class SecurityRouteProperties {

    private List<String> publicRoutes = List.of();

    /**
     * Header carrying the real client address when the gateway sits behind a proxy or load balancer
     * (for example {@code X-Forwarded-For}); the first address in it is used. Empty uses the socket's
     * remote address. Set it only behind a proxy that overwrites the header — otherwise a client
     * could choose its own address and dodge the per-address limits.
     */
    private String clientIpHeader = "";

    /** Requests per minute per client address to the public {@code /v1/auth/**} routes (signup, login). */
    private int publicAuthRequestsPerMinute = 120;

    /** Failed authentications per minute per client address before further attempts are refused. */
    private int maxFailedAuthPerMinute = 30;
}
