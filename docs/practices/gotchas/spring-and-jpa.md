# Pitfalls: Spring and JPA

## A Lombok builder drops field initializers

- **Symptom:** an insert fails with a not-null constraint violation on a column that has a default in the entity (`status`, `enabled`, `attempts`).
- **Cause:** `@Builder` ignores a field's initializer unless the field is marked `@Builder.Default`, so the builder yields `null`. This hit three entities in the monolith before the fourth was caught from the compiler warning.
- **Fix:** add `@Builder.Default` to every field with a default value, and treat the compiler's warning as an error.

## MapStruct silently leaves a mismatched field `null`

- **Symptom:** a response field is always `null` although the entity has a value — e.g. an order's `status` or a new merchant's `merchantStatus`.
- **Cause:** MapStruct matches by name; the entity's `orderStatus` doesn't match the DTO's `status`, so nothing is mapped and only a warning is printed.
- **Fix:** add an explicit `@Mapping` — `OrderMapper` maps `orderStatus` → `status` and `MerchantMapper` maps `status` → `merchantStatus` — and fix every "Unmapped target property" warning. Both mappers shipped without it and returned `null` until an end-to-end check caught it.

## PostgreSQL rejects the JVM's legacy time-zone name

- **Symptom:** `FATAL: invalid value for parameter "TimeZone": "Asia/Calcutta"` on the first database connection, typically on Windows.
- **Cause:** the JVM reports the legacy zone alias, and PostgreSQL rejects it.
- **Fix:** run with `-Duser.timezone=Asia/Kolkata` (required for the monolith's tests). A permanent fix would pin it in the surefire `argLine` or the JVM options.

## Adding Spring Security locks every endpoint

- **Symptom:** every request is `401` behind HTTP Basic with a random password printed at start-up, after adding `spring-boot-starter-security` only for its crypto classes.
- **Cause:** the starter's auto-configuration secures everything unless a `SecurityFilterChain` bean exists. (In the monolith, two *unscoped* chains then failed start-up with `UnreachableFilterChainException`.)
- **Fix:** the services depend on `spring-security-crypto` alone — bcrypt and AES without the filter chain. Keep it that way; authentication belongs to the gateway.

## An exception passed to `HandlerExceptionResolver` with no handler becomes an empty response

- **Symptom:** a filter-level failure returns an empty `200` (or an unshaped body) instead of an error.
- **Cause:** a servlet filter that hands an exception to `HandlerExceptionResolver` gets nothing back when no `@ExceptionHandler` covers that type, and the response is left in its default state. The monolith hit this with jjwt's `JwtException`.
- **Fix:** every exception type thrown from a filter needs a handler in `GlobalExceptionHandler` — `IdempotencyConflictException` has one.

## An unmapped exception is a `500`

- **Symptom:** a business rule violation returns `500 INTERNAL_ERROR`.
- **Cause:** a bare `RuntimeException` (or a new exception type) falls through to the catch-all handler — rotating a revoked API key does this today.
- **Fix:** throw one of `common-lib`'s typed exceptions, or add a handler for a new one.

## Open-Session-In-View holds a connection across remote calls

- **Symptom:** under load, payment-service logs `Connection is not available, request timed out` with every pool connection active, while PostgreSQL shows those same connections idle — held by the application but not running queries.
- **Cause:** `spring.jpa.open-in-view` is on by default (Spring logs a warning about it at startup). It binds a persistence context to the whole HTTP request, and once the first query runs, the JDBC connection stays with the request until it ends — including across the Feign call to vault-service during payment initiation. That undoes the saga's point of keeping remote calls out of transactions: connections are held for the duration of a remote call.
- **Fix:** `spring.jpa.open-in-view: false` in `config-repo/application.yaml`, for every service. Connections are now held only inside `@Transactional` methods. Nothing relied on lazy loading outside a transaction.

## `saveAll` on detached entities is a `SELECT` and an `UPDATE` per row

- **Symptom:** marking a batch of 500 outbox rows `PUBLISHED` takes far longer than the Kafka sends it follows.
- **Cause:** the rows were loaded in an earlier transaction, so they are detached; `saveAll` merges each one, which re-reads it and then updates it. JDBC batching groups the updates but not the reads.
- **Fix:** a `@Modifying` bulk `UPDATE … WHERE id IN (:ids)` — `OutboxEventRepository.markPublished`. A bulk update skips auditing, so it sets `updatedAt` itself.

## With virtual threads on, scheduled jobs share one thread

- **Symptom:** a `@Scheduled(fixedDelay = …)` job runs late or in bursts — the outbox backlog rose and fell in a ~10-second sawtooth under load.
- **Cause:** `spring.threads.virtual.enabled: true` makes Spring Boot's scheduler a `SimpleAsyncTaskScheduler`, which runs every fixed-delay job on its single scheduler thread and ignores `spring.task.scheduling.pool.size`. While `BankCallbackSimulator` worked through 500 payments, `OutboxPoller` could not start.
- **Fix:** `SchedulingConfig` in payment-service and operations-service declares a `ThreadPoolTaskScheduler`, sized by `spring.task.scheduling.pool.size` in `config-repo`. Raise it when a service gains more scheduled jobs than threads.

## PostgreSQL's defaults are sized for a tiny machine

- **Symptom:** under write load, sessions queue on the WAL flush and throughput is far below what the CPU allows.
- **Cause:** out of the box PostgreSQL has 128 MB of `shared_buffers` and checkpoints every 1 GB of WAL — against a payment database of several gigabytes with randomly ordered UUID indexes.
- **Fix:** `services.docker-compose.yaml` starts it with `shared_buffers=2GB`, `max_wal_size=8GB`, `checkpoint_timeout=15min`, `wal_compression=lz4` and group commit; the Kubernetes StatefulSet uses the same settings with smaller memory values (`shared_buffers=512MB`) to fit its 1536Mi limit. `fsync` and `synchronous_commit` stay on — a payment system can't trade durability for speed. This took the load test from 660 to 963 req/s.

## Size the connection pool, and fail fast when it's exhausted

- **Symptom:** requests stall for exactly 30 seconds, then fail — and because the circuit breakers wrapped whole service methods, those failures opened the merchant-service breaker although merchant-service was healthy.
- **Cause:** HikariCP's defaults — 10 connections and a 30-second wait for one.
- **Fix:** each service's pool is sized in `config-repo/<service>.yaml` (payment 40, merchant 20, vault and operations 10, each overridable with `<SERVICE>_DB_POOL_SIZE`) with `connection-timeout: 5000`. Keep the sum under PostgreSQL's `max_connections` — 100 locally, 300 on Kubernetes.
