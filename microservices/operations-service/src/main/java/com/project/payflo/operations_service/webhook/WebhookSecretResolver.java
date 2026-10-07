package com.project.payflo.operations_service.webhook;

import feign.FeignException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The signing secret of a webhook config, as merchant-service holds it now. A delivery is signed when it is sent, so
 * this is asked once per attempt; the answer is remembered in memory for a short time (never in Redis or the database:
 * it is a secret), which is also how long a rotation can take to reach a delivery already on its way.
 */
@Component
public class WebhookSecretResolver {

    private record Entry(String secret, long expiresAtNanos) {}

    private final WebhookSecretGateway gateway;
    private final long ttlNanos;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    public WebhookSecretResolver(WebhookSecretGateway gateway,
                                 @Value("${app.webhook.target-cache-ttl-seconds:30}") long ttlSeconds) {
        this.gateway = gateway;
        this.ttlNanos = Duration.ofSeconds(ttlSeconds).toNanos();
    }

    /**
     * The secret, or empty if the merchant has deleted that webhook config. Throws when merchant-service can't be
     * asked, which the delivery treats as a failed attempt to retry.
     */
    public Optional<String> secretFor(UUID merchantId, UUID configId) {
        long now = System.nanoTime();
        Entry entry = entries.get(configId);
        if (entry != null && now - entry.expiresAtNanos() < 0) {
            return Optional.of(entry.secret());
        }
        try {
            String secret = gateway.getWebhookTarget(merchantId, configId).webhookSecret();
            if (ttlNanos > 0) {
                entries.put(configId, new Entry(secret, now + ttlNanos));
            }
            return Optional.of(secret);
        } catch (FeignException.NotFound gone) {
            entries.remove(configId);
            return Optional.empty();
        }
    }
}
