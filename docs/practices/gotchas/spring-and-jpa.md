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

## Size the connection pool, and fail fast when it's exhausted

- **Symptom:** requests stall for exactly 30 seconds, then fail — and because the circuit breakers wrapped whole service methods, those failures opened the merchant-service breaker although merchant-service was healthy.
- **Cause:** HikariCP's defaults — 10 connections and a 30-second wait for one.
- **Fix:** each service's pool is sized in `config-repo/<service>.yaml` (payment 40, merchant 20, vault and operations 10, each overridable with `<SERVICE>_DB_POOL_SIZE`) with `connection-timeout: 5000`. Keep the sum under PostgreSQL's `max_connections` — 100 locally, 300 on Kubernetes.
