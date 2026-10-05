package com.project.payflo.common_lib.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

@Component
@Slf4j
@RequiredArgsConstructor
public class RedisApiKeyCache implements ApiKeyCache {

    private static final String PREFIX = "apikey:";
    private static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<ApiKeyCacheEntry> get(String keyId) {
        try {
            String json = stringRedisTemplate.opsForValue().get(PREFIX+keyId);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, ApiKeyCacheEntry.class));
        } catch (Exception e) {
            log.warn("ApiKey cache read filed, keyId: {}", keyId);
            return Optional.empty();
        }
    }

    @Override
    public void put(String keyId, ApiKeyCacheEntry entry) {
        try {
            stringRedisTemplate.opsForValue().set(PREFIX+keyId,
                    objectMapper.writeValueAsString(entry),
                    TTL);
        } catch (Exception e) {
            log.warn("ApiKey cache put failed, keyId: {}", keyId);
        }
    }

    @Override
    public void evict(String keyId) {
        try {
            stringRedisTemplate.delete(PREFIX+keyId);
        } catch (Exception e) {
            // The entry then lives out its TTL (5 minutes), so a revoked key can still work for that long.
            log.error("ApiKey cache evict failed, keyId: {}", keyId, e);
        }
    }
}












