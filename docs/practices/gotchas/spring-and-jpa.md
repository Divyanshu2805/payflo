# Pitfalls: Spring and JPA

## A Lombok builder drops field initializers

- **Symptom:** an insert fails with a not-null constraint violation on a column that has a default in the entity (`status`, `enabled`, `attempts`).
- **Cause:** `@Builder` ignores a field's initializer unless the field is marked `@Builder.Default`, so the builder yields `null`. This hit three entities in the monolith before the fourth was caught from the compiler warning.
- **Fix:** add `@Builder.Default` to every field with a default value, and treat the compiler's warning as an error.

## MapStruct silently leaves a mismatched field `null`

- **Symptom:** a response field is always `null` although the entity has a value — e.g. an order's `status`.
- **Cause:** MapStruct matches by name; the entity's `orderStatus` doesn't match the DTO's `status`, so nothing is mapped and only a warning is printed.
- **Fix:** add an explicit `@Mapping(source = "orderStatus", target = "status")` (as `OrderMapper` does) and fix every "Unmapped target property" warning.

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
