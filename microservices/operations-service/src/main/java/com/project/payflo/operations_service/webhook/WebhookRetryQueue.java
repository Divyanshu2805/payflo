package com.project.payflo.operations_service.webhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookRetryQueue {

    private final StringRedisTemplate redisTemplate;

    @Value("${app.webhook.delivery.redis-key:webhook-retry}")
    private String key;

    public void enqueue(UUID webhookEventId, LocalDateTime retryAt) {
        long time = getTime(retryAt);
        redisTemplate.opsForZSet().add(key, webhookEventId.toString(), time);
        log.debug("Enqueued a webhook event with id: {}", webhookEventId);
    }

    /** Queues many events in one Redis call (one ZADD with many members). */
    public void enqueueAll(Map<UUID, LocalDateTime> dueAt) {
        if (dueAt.isEmpty()) return;
        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        dueAt.forEach((id, retryAt) ->
                tuples.add(ZSetOperations.TypedTuple.of(id.toString(), (double) getTime(retryAt))));
        redisTemplate.opsForZSet().add(key, tuples);
        log.debug("Enqueued {} webhook events", dueAt.size());
    }

    /** Takes up to {@code limit} events that are due off the queue, in one read and one removal. */
    public Set<UUID> pollDue(int limit) {
        long now = getTime(LocalDateTime.now());
        Set<String> due = redisTemplate.opsForZSet().rangeByScore(key, 0, now, 0, limit);

        if (due == null || due.isEmpty()) return Set.of();

        redisTemplate.opsForZSet().remove(key, due.toArray());

        return due.stream()
                .map(UUID::fromString)
                .collect(Collectors.toSet());
    }

    public void enqueueIfAbsent(UUID id, LocalDateTime nextRetryAt) {
        redisTemplate.opsForZSet().addIfAbsent(key, id.toString(), getTime(nextRetryAt));
    }

    private static long getTime(LocalDateTime retryAt) {
        return retryAt.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
