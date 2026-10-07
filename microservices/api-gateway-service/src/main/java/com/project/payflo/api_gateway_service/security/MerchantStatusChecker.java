package com.project.payflo.api_gateway_service.security;

import com.project.payflo.api_gateway_service.client.ApiKeyLookupClient;
import com.project.payflo.common_lib.cache.MerchantStatusCacheKey;
import com.project.payflo.common_lib.enums.MerchantStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Refuses requests from a suspended merchant, whatever credential they carry — an API key or a JWT
 * that was issued before the suspension. The status is cached for a minute, so a suspension made
 * any other way takes effect within that time; the admin API's suspend and reactivate write the
 * cache themselves ({@link MerchantStatusCacheKey}), so those apply at once. If the status can't be looked up the request is let through: a
 * merchant-service outage shouldn't stop every merchant from taking payments.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MerchantStatusChecker {

    private final StringRedisTemplate redis;
    private final ApiKeyLookupClient lookupClient;

    public void requireNotSuspended(String merchantId) {
        if (statusOf(merchantId) == MerchantStatus.SUSPENDED) {
            throw new MerchantSuspendedException();
        }
    }

    private MerchantStatus statusOf(String merchantId) {
        String cacheKey = MerchantStatusCacheKey.of(merchantId);
        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null) {
                return MerchantStatus.valueOf(cached);
            }
        } catch (Exception e) {
            log.warn("Merchant status cache read failed, merchantId: {}", merchantId);
        }

        MerchantStatus status;
        try {
            status = lookupClient.findMerchantStatus(UUID.fromString(merchantId));
        } catch (Exception e) {
            log.warn("Merchant status lookup failed, letting the request through, merchantId: {}", merchantId, e);
            return null;
        }

        try {
            redis.opsForValue().set(cacheKey, status.name(), MerchantStatusCacheKey.TTL);
        } catch (Exception e) {
            log.warn("Merchant status cache put failed, merchantId: {}", merchantId);
        }
        return status;
    }
}
