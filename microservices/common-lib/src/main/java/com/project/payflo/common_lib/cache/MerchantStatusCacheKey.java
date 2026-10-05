package com.project.payflo.common_lib.cache;

import java.time.Duration;
import java.util.UUID;

/**
 * Where, and for how long, the gateway remembers a merchant's status in Redis. merchant-service writes the same key
 * when it suspends or reactivates a merchant, so the change applies at once instead of when the entry expires.
 */
public final class MerchantStatusCacheKey {

    public static final String PREFIX = "merchant:status:";

    public static final Duration TTL = Duration.ofSeconds(60);

    private MerchantStatusCacheKey() {
    }

    public static String of(UUID merchantId) {
        return PREFIX + merchantId;
    }

    public static String of(String merchantId) {
        return PREFIX + merchantId;
    }
}
