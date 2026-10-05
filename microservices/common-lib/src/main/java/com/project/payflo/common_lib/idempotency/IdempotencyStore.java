package com.project.payflo.common_lib.idempotency;

import java.time.Duration;
import java.util.Optional;

public interface IdempotencyStore {

    String IN_PROGRESS = "__IN_PROGRESS__";

    // Claims the key with a placeholder (IN_PROGRESS plus the request fingerprint) if nobody has.
    boolean setIfAbsent(String key, String placeholder, Duration ttl);

    void store(String key, String value, Duration ttl);

    Optional<String> get(String key);

    void delete(String key);
}
