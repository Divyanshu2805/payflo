package com.project.payflo.common_lib.idempotency;

import com.project.payflo.common_lib.cache.ApiKeyCache;
import com.project.payflo.common_lib.cache.RedisApiKeyCache;
import com.project.payflo.common_lib.context.MerchantContext;
import com.project.payflo.common_lib.ratelimit.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerExceptionResolver;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@AutoConfiguration
public class SharedResilienceAutoConfiguration {

    // Responses that carry a secret shown only once: the filter remembers the request ran, never what it returned.
    private static final List<String> UNREPLAYABLE_PATHS = List.of(
            "/v1/merchants/api-keys",
            "/v1/merchants/api-keys/*/rotate",
            "/v1/merchants/webhooks",
            "/v1/merchants/webhooks/*/rotate-secret");

    // Request bodies holding card data, passwords or account numbers: their fingerprint ignores the body, because a
    // hash of one of those is small enough to guess offline and must not be kept in Redis.
    private static final List<String> BODY_EXCLUDED_PATHS = List.of(
            "/v1/vault/**",
            "/v1/auth/**",
            "/v1/merchants/users",
            "/v1/merchants/me/settlement-bank");

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    // Counters over a window, for the velocity rules (card tokenization, card-testing detection).
    @Bean
    public VelocityCounters velocityCounters(StringRedisTemplate stringRedisTemplate) {
        return new VelocityCounters(stringRedisTemplate);
    }

    @Bean
    public ApiKeyCache apiKeyCache(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        return new RedisApiKeyCache(stringRedisTemplate, objectMapper);
    }

    @Bean
    public IdempotencyStore idempotencyStore(StringRedisTemplate stringRedisTemplate) {
        return new RedisIdempotencyStore(stringRedisTemplate);
    }

    @Bean
    public IdempotencyFilter idempotencyFilter(MerchantContext merchantContext,
                                               IdempotencyStore idempotencyStore,
                                               @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver) {
        return new IdempotencyFilter(merchantContext, idempotencyStore, handlerExceptionResolver,
                UNREPLAYABLE_PATHS, BODY_EXCLUDED_PATHS);
    }

    @Bean
    @ConditionalOnProperty(name = "app.rate-limit.method", havingValue = "fixed")
    public RateLimiter fixedWindowRateLimiter(StringRedisTemplate stringRedisTemplate) {
        return new FixedWindowRateLimiter(stringRedisTemplate);
    }

    // "sliding" and "sliding-lua" are the same limiter. "sliding" used to be a separate implementation that
    // checked and added in two steps, so concurrent requests could all pass at the limit; it is now the
    // atomic Lua one, and the old name is kept so existing configuration keeps working.
    @Bean
    @ConditionalOnProperty(name = "app.rate-limit.method", havingValue = "sliding")
    public RateLimiter slidingWindowRateLimiter(StringRedisTemplate redis) {
        return new SlidingWindowLuaLimiter(redis);
    }

    @Bean
    @ConditionalOnProperty(name = "app.rate-limit.method", havingValue = "sliding-lua")
    public RateLimiter slidingWindowLuaLimiter(StringRedisTemplate redis) {
        return new SlidingWindowLuaLimiter(redis);
    }

    @Bean
    @ConditionalOnProperty(name = "app.rate-limit.method", havingValue = "bucket")
    public RateLimiter tokenBucketRateLimiter(StringRedisTemplate redis) {
        return new TokenBucketRateLimiter(redis);
    }

}





















