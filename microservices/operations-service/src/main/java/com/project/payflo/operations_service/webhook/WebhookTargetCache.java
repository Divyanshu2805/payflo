package com.project.payflo.operations_service.webhook;

import com.project.payflo.common_lib.dto.WebhookTarget;
import com.project.payflo.operations_service.client.MerchantServiceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// A merchant's webhook targets for an event type, remembered for a short time. Asking merchant-service
// for every consumed event capped the consumer at a few hundred events a second. Kept in memory only,
// never in Redis: a target carries its signing secret. A config change takes up to the TTL to apply.
@Component
public class WebhookTargetCache {

    private record Key(UUID merchantId, String eventType) {}

    private record Entry(List<WebhookTarget> targets, long expiresAtNanos) {}

    private final MerchantServiceClient merchantServiceClient;
    private final long ttlNanos;
    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();

    public WebhookTargetCache(MerchantServiceClient merchantServiceClient,
                              @Value("${app.webhook.target-cache-ttl-seconds:30}") long ttlSeconds) {
        this.merchantServiceClient = merchantServiceClient;
        this.ttlNanos = Duration.ofSeconds(ttlSeconds).toNanos();
    }

    public List<WebhookTarget> activeTargetsFor(UUID merchantId, String eventType) {
        Key key = new Key(merchantId, eventType);
        long now = System.nanoTime();
        Entry entry = entries.get(key);
        if (entry != null && now - entry.expiresAtNanos() < 0) {
            return entry.targets();
        }
        // A failed lookup throws and caches nothing, so the consumer handles it as before.
        List<WebhookTarget> targets = merchantServiceClient.getActiveConfigsForEvent(merchantId, eventType);
        entries.put(key, new Entry(targets, now + ttlNanos));
        return targets;
    }
}
