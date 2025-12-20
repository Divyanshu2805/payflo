# Known gaps vs. requirements / v1 design

[← Back to docs index](README.md)

Found while syncing docs to the actual implementation (2025-10-06) — not yet triaged as intentionally
dropped vs. still planned:

1. ~~`ORDER_RECORD` has no `idempotency_key`~~ — **partially resolved (2025-11-24):** added
   `OrderRecord.idempotencyKey` (nullable). Schema-only — `CreateOrderRequest` has no field for the
   caller to supply one, and `OrderServiceImpl.create` neither reads nor checks it, so retrying a
   create-order request still creates a duplicate order rather than returning the original. Closing
   that requires accepting an `X-Idempotent-Header` (or a request-body field) and a lookup-before-
   create, neither of which exists yet.
2. ~~`API_KEY` has no `webhook_secret_hash`~~ — **resolved (2025-11-24):** added
   `ApiKey.webhookSecretHash` (nullable, unmapped to any DTO — same "never returned" pattern as
   `keySecretHash`). Schema-only for now; nothing writes or reads it yet, since webhook delivery
   itself isn't built.
3. ~~`CUSTOMER` has no `gst_id`~~ — **resolved (2025-11-24):** added `Customer.gstId` (nullable).
   Schema-only — `Customer` still has no repository/service/controller layer at all, so nothing
   populates it yet.
4. ~~`PAYMENT` has no running `refunded_amount` total~~ — **partially resolved (2025-11-24):** added
   `Payment.refundedAmount` (a second `Money`, `@AttributeOverride`d onto
   `refunded_amount_units`/`refunded_currency` so it doesn't collide with the existing `amount`
   columns). Schema-only — refunds aren't built at all yet (no `RefundService`, no endpoint), so
   nothing ever increments it; the value would need to be derived by summing `REFUND` rows until
   that exists.
5. ~~`PAYMENT_TRANSITION_LOG` has no `reason` field~~ — **resolved (2025-11-24):** added
   `PaymentTransitionLog.reason` (nullable, `varchar(500)`). `PaymentTransitionService.apply`
   gained an overload taking a `reason` string (the no-reason `apply(Payment, PaymentEvent)` now
   just delegates with `null`); `PaymentServiceImpl` passes the processor's `errorDescription` as
   the reason on every `AUTHORIZE_FAIL`/`CAPTURE_FAIL` transition. Every other transition
   (`AUTHORIZE_ATTEMPT`, `CAPTURE_REQUEST`, `CAPTURE_SUCCESS`, `CAPTURE_PENDING`,
   `AUTHORIZE_SUCCESS`) still logs `reason = null` — there isn't a similarly natural string to
   attach to those yet.
6. ~~Secrets are stored unhashed~~ — **resolved (2025-11-24):** `AuthServiceImpl.signup` hashes the
   password with the injected `PasswordEncoder` (`BCryptPasswordEncoder`) bean from
   `WebSecurityConfig`, and `ApiKeyServiceImpl.create`/`.rotate` now do the same for the generated
   API key secret — though via their own locally-instantiated `new BCryptPasswordEncoder()` field
   rather than the shared bean (functionally identical, same default strength, just not reusing the
   bean already available for injection). The raw value is only ever returned once in the response
   (`ApiKeyCreateResponse`, built directly from the raw string rather than mapped off the now-hashed
   entity field); `ApiKey.keySecretHash`/`previousKeySecretHash` store only the hash.
   `ApiKeyAuthenticationFilter.secretMatches` verifies against either (grace-period-aware) using its
   own separate `BCryptPasswordEncoder` instance too — three instances of the same encoder across
   the codebase where one shared bean would do.
   `ApiKeyMapper.toCreateResponse` was deleted since it mapped `keySecretHash` straight to the
   response and would have leaked the hash instead of the raw secret otherwise.
7. ~~`OrderController`/`PaymentController` use a hardcoded `merchantId`~~ — **resolved
   (2025-11-24):** all four merchant-scoped controllers (`OrderController`, `PaymentController`,
   `VaultController`, `ApiKeyController`) now inject `merchant/security/MerchantContext` (a
   `@RequestScope` bean) and call `merchantContext.getMerchantId()` instead of a fixed test UUID.
   `JwtAuthenticationFilter` populates it from the `merchant_id` claim on the caller's JWT. This is
   also why `/v1/orders/**`/`/v1/payments/**`/`/v1/vault/**` now require authentication (see gap
   13) — with no request-derived merchant, there'd be nothing for `MerchantContext` to hold.
8. **`Payment.idempotencyKey` is a fresh random value every call, never checked** —
   `PaymentServiceImpl.initiate` generates `UUID.randomUUID().toString()` per request instead of
   accepting/deriving a caller-supplied key and looking up an existing `Payment` by it, so retrying
   a payment-initiation request creates a duplicate `Payment` row rather than returning the
   original.
9. **`BankCallbackSimulator` — the mechanism that would resolve `AUTHORIZING` payments — is built
   but not scheduled, so `POST /v1/payments/{paymentId}/capture` still can never succeed today.**
   Briefly enabled (2025-11-24) via `@EnableScheduling`/`@Scheduled`, then deliberately disabled
   again the same day — both `PayFloApplication`'s `@EnableScheduling` and
   `processCallbacks()`'s `@Scheduled` are commented out once more. While disabled, nothing ever
   moves a payment out of `AUTHORIZING`, so `resolveAuthorization` (fully implemented — fires
   `AUTHORIZE_SUCCESS`/`AUTHORIZE_FAIL`, then auto-captures and marks the order `PAID` on approval)
   is unreachable in practice, and a manual `capture` call always gets rejected with `409
   INVALID_STATE_TRANSITION`. Re-enabling this remains the deliberate, explicit decision described
   in [CLAUDE.md](CLAUDE.md) — not something to toggle back on as a side effect of other work.
10. **`PaymentTransitionService`'s `actor` is hardcoded to `SYSTEM`** — every `PaymentTransitionLog`
    row is written with `actor = PaymentActor.SYSTEM` (`//TODO: fetch merchant context to identify
    actor`). Auth context now exists (`MerchantContext`, resolved by either the JWT or API-key
    filter) — this just hasn't been updated to read it yet, unlike `audit/AuditorAwareImpl` (added
    2025-12-05), which reads the exact same bean, the same way, for the exact same reason
    (`createdBy`/`updatedBy`), including the `try/catch` needed because `MerchantContext` is
    `@RequestScope` and `PaymentTransitionService.apply` can run outside a request too (from
    `BankCallbackSimulator`, currently disabled). That's the template to copy here.
    `PaymentTransitionLogRepository` is also currently a bare `JpaRepository` — no custom finder
    methods yet (nothing reads the log back out through the API today).
11. **`vault.master-key` has a hardcoded dev-only default** in `application.yaml`
    (overridable via `VAULT_MASTER_KEY`) — fine for local development, but a real deployment must
    set a real secret via that env var and keep it in a proper secret store, not source control. If
    this key is ever lost or rotated without a re-encryption migration, every previously-vaulted
    card's DEK becomes permanently unwrappable. `POST /v1/vault/tokenize` now takes its `merchantId`
    from `MerchantContext` like the other endpoints (see gap 7), rather than a hardcoded value.
    `VaultServiceImpl.charge` decrypts the
    PAN into a Java `String` before handing it to `PaymentProcessorRequest.card(...)` — the raw
    `byte[]` is zeroed in a `finally` block after use, but the `String` copy is immutable and can't
    be zeroed, so it lingers on the heap until garbage collected (a well-known, hard-to-avoid
    limitation of using `String` for sensitive data in Java; a hardened version would carry the PAN
    as `char[]`/`byte[]` end-to-end instead).
12. ~~`POST /v1/auth/login` can't authenticate a real merchant~~ — **resolved (2025-11-24):**
    `WebSecurityConfig` defines a real `PasswordEncoder` (`BCryptPasswordEncoder`) and
    `AuthenticationManager` (`DaoAuthenticationProvider` wired to
    `merchant/security/MerchantUserDetailsService`, which loads an `AppUser` — `implements
    UserDetails` — by email via `AppUserRepository`), and `AuthServiceImpl.signup` now hashes the
    password before storing it (gap 6). A correct email/password now authenticates successfully and
    returns a real JWT. Two related bugs found and fixed alongside it: `MerchantUserDetailsService`
    was throwing `ResourceNotFoundException` for an unknown email — a plain exception
    `DaoAuthenticationProvider` doesn't special-case, so it leaked a `404` revealing whether an
    email was registered, distinguishable from a `401` for a wrong password on an existing account.
    It now throws `UsernameNotFoundException`, which `DaoAuthenticationProvider` deliberately
    converts to the same generic `BadCredentialsException` either way. Second, nothing handled
    `AuthenticationException` at all, so a bad-credentials failure fell through to Spring Security's
    default entry point and returned a bare `403` with no body; `GlobalExceptionHandler` now maps
    any `AuthenticationException` to `401` with `INVALID_CREDENTIALS`. The returned JWT is now also
    validated on later requests (`JwtAuthenticationFilter`, see gap 13) — login is fully functional
    end to end. See [APIs](api.md) for the full current behavior.
13. ~~`/v1/merchants/**` requires authentication with no way to provide it~~ — **resolved
    (2025-11-24):** `merchant/security/JwtAuthenticationFilter` — a `OncePerRequestFilter` on
    `jwtChain` — reads the `Authorization: Bearer <token>` header, verifies it via `JwtUtil`,
    populates `SecurityContextHolder`, and sets the resolved merchant on
    `merchant/security/MerchantContext` (a `@RequestScope` bean) from the token's `merchant_id`
    claim. `ApiKeyController` (the only controller on `jwtChain`'s routes) reads it from there.
    `/v1/orders/**`/`/v1/payments/**`/`/v1/vault/**` also now require authentication (a real
    behavior change; they were previously fully open) — but via a second, separate mechanism, see
    gap 14: `apiKeyChain` and `ApiKeyAuthenticationFilter`, not a JWT.
14. **No role/permission distinction within a merchant.** `ApiKeyAuthenticationFilter` and
    `JwtAuthenticationFilter` both resolve *which merchant* is calling (`MerchantContext`), but
    neither carries any notion of *what that caller is allowed to do* — `AppUser.role`
    (`OWNER`/`ADMIN`/`TEAM`) is never read anywhere outside `JwtUtil.generateAccessToken` putting it
    on the token as an unused claim, and an API key has no scope/permission concept at all beyond
    which merchant issued it. Any authenticated user or API key can act as that merchant everywhere
    — create orders, capture payments, tokenize cards, manage other API keys — with no finer-grained
    check.
15. **`ApiKey.lastUsedAt` is never written.** The field exists specifically to track "last time this
    key authenticated a request" (see the field's own description), but
    `ApiKeyAuthenticationFilter.doFilterInternal` doesn't update it on a successful match — every
    key's `lastUsedAt` stays `null` forever, regardless of how often it's used.
16. ~~A stale `Authorization: Bearer` header on a `permitAll()` route returns an empty `200`
    instead of a `401`~~ — **resolved (2025-12-08), found while adding refresh tokens:**
    `JwtAuthenticationFilter` runs on every `jwtChain` request *including* `permitAll()` ones (only
    `securityMatcher` scopes the chain, not the authorization rule), so a client attaching a
    stale/expired access token to a call it's making *because* that token just expired — exactly
    what a real frontend does when it hits `/v1/auth/refresh` — would throw from
    `jwtUtil.verifyAccessToken()`, get caught by the filter, and be hex to
    `HandlerExceptionResolver`, which had nothing registered for jjwt's `JwtException` and so left
    the response in its untouched default state. `GlobalExceptionHandler` now maps
    `io.jsonwebtoken.JwtException` to `401` (`INVALID_ACCESS_TOKEN`) — verified by hand: a garbled
    Bearer token on a `/v1/auth/refresh` call now returns a clean `401` instead of an empty `200`.
17. **`MethodArgumentNotValidException` has a `@ExceptionHandler` in `GlobalExceptionHandler`, but
    it's never actually invoked.** Found while testing refresh tokens (a blank `refreshToken` on
    `POST /v1/auth/refresh` should trigger it) and confirmed it's pre-existing and unrelated to
    that feature — `POST /v1/auth/login` with a blank `password` reproduces the identical symptom.
    The app's own log shows `DefaultHandlerExceptionResolver` (Spring's built-in resolver, not our
    `@RestControllerAdvice`) resolving the exception and producing Spring Boot's generic error body
    (`{"timestamp":...,"status":400,"error":"Bad Request","path":...}`) instead of the project's
    `ErrorResponse`/`VALIDATION_FAILED` shape — even though `GlobalExceptionHandler`'s *other*
    handlers (`AuthenticationException`, `BadRequestException`, `InvalidRefreshTokenException`) all
    fire correctly, so this isn't a broad wiring problem with the class itself, just this one
    exception type. The HTTP status code (`400`) is still correct either way — this is a response
    *shape* inconsistency, not a security or correctness bug — but it means every `@Valid`
    validation failure across the whole API returns a differently-shaped error body than everything
    else. Root cause not yet confirmed; flagged rather than guessed at.
18. **No absolute cap on refresh-token session age.** `RefreshTokenService.issue` always sets
    `expiresAt = now + 7 days` on every rotation, including ones minted by `/v1/auth/refresh` itself
    — so a session refreshed at least once a week never truly expires. A cheap follow-up (not built
    now): carry the original issuance time forward across rotations and cap total lineage age
    independent of the per-token sliding window.
19. **No cleanup job for expired/revoked `refresh_token` rows.** The table grows forever. Not built
    now on purpose — `CLAUDE.md` treats `@EnableScheduling`/background jobs against auth/payment
    data as a deliberate, individually-made decision (flipped on and back off once already this
    session for `BankCallbackSimulator`), not something to bundle in as a side effect of an
    unrelated feature.
20. **The four rate limiters don't behave the same when Redis is unavailable, and two have
    correctness holes.** `SlidingWindowLuaLimiter` and `TokenBucketRateLimiter` catch
    `DataAccessException` and fail open. `FixedWindowRateLimiter` and `SlidingWindowRateLimiter`
    don't — a Redis outage surfaces as an unhandled error on every API-key request instead of
    letting traffic through. `FixedWindowRateLimiter` also does `INCR` and `EXPIRE` as two separate
    calls, so a crash between them leaves a counter with no TTL that never resets (a permanent
    lockout for that key), and its `ttl != null & ttl > 0` uses a non-short-circuit `&` that would
    throw on a `null` TTL. `SlidingWindowRateLimiter` (non-Lua) does check-then-add as separate
    calls, so concurrent requests can both pass at the limit. The configured default
    (`app.rate-limit.method: fixed`) is one of the two that fail closed. Separately, rate limiting
    runs *after* the API key is authenticated, so failed-authentication attempts (wrong keyId or
    secret) are not counted or throttled at all.
21. **Idempotency filter gaps** (found by reading the code while documenting it; not exercised
    against a running instance). (a) `GlobalExceptionHandler` has no handler for
    `IdempotencyConflictException`, and the filter hands it to `HandlerExceptionResolver` directly
    — with nothing registered the exception isn't resolved, so a retry that arrives while the
    original is still running is meant to get a `409` but there's nothing that produces one; the
    response is left in its default state. (b) The filter isn't scoped to the API-key chain, so it
    also wraps `/v1/auth/**` routes, where `MerchantContext` has no merchant yet and the key
    becomes just the raw client-supplied header value with no merchant prefix — two different
    clients sending the same `X-Idempotency-Key` to `/v1/auth/login` would share one Redis entry,
    and the second would be replayed the first's stored response (which contains tokens). (c) The
    key isn't tied to the request body, so the same key with a different payload silently replays
    the first response instead of being rejected. (d) `replay()` is missing a `return` after
    handling a malformed stored value, so it would fall through and throw
    `StringIndexOutOfBoundsException` (not reachable today, since nothing writes such a value). (e)
    A stray `import java.awt.*;` is left in `IdempotencyFilter`.
22. **The API key cache is never evicted.** `ApiKeyCache.evict` exists but nothing calls it —
    `ApiKeyServiceImpl.revoke` (sets `enabled = false`) and `.rotate` (swaps the secret hashes and
    opens the 24h grace period) both change the database row without touching Redis. Consequences,
    from reading the code (not yet exercised against a running instance): a revoked key keeps
    authenticating for up to the 5-minute TTL, and a freshly rotated key's *new* secret is compared
    against the stale cached hash and rejected for up to the TTL, while the cached entry has no
    `previousKeySecretHash` to fall back on. Also: an unknown `keyId` isn't negatively cached, so
    every request with a bogus key still reaches Postgres, and `RedisApiKeyCache.evict` (unlike
    `get`/`put`) has no `try/catch`, so a Redis outage would make it throw.
23. **`BusinessRuleViolationException` has no `@ExceptionHandler`.** Introduced (2025-12-16) to
    replace `ConflictException` at two throw sites (`OrderServiceImpl.cancel`'s
    `ORDER_CANNOT_CANCEL`, `PaymentServiceImpl.initiate`'s `ORDER_NOT_PAYABLE`), but
    `GlobalExceptionHandler` was never given a matching handler the way it has one for
    `ConflictException` (still used elsewhere, e.g. `ApiKeyServiceImpl`). Both of those calls
    currently surface as an unhandled `500` instead of the intended `409`.
24. **`PaymentTransitionLog.reason` regressed back to always `null` on `AUTHORIZE_FAIL`/
    `CAPTURE_FAIL`, and `PaymentServiceImpl.capture` silently drops `Pending`/`null` capture
    results.** Known gap 5 describes `PaymentServiceImpl` passing the processor's
    `errorDescription` as the transition reason — while adding outbox event publishing
    (2025-12-16) those three call sites were rewritten from the three-arg
    `paymentTransitionService.apply(payment, event, reason)` to the two-arg overload, dropping the
    reason again. `errorCode`/`errorDescription` are still set on the `Payment` row itself, so the
    failure detail isn't lost — just no longer mirrored onto the transition log row that's meant to
    carry it. The same commit also rewrote `capture`'s exhaustive `switch` over `PaymentResult`
    into an `if PaymentResult.Success ... else if PaymentResult.Failure ...` chain with no branch
    for `Pending` or `null` — previously `Pending` fired `CAPTURE_PENDING` and a `null` result set
    `status = AUTHORIZED` directly; now either case falls through doing nothing; the payment stays
    `CAPTURING` (set by the preceding `CAPTURE_REQUEST` transition) with no error recorded, and the
    endpoint still returns `200 OK`. Currently dormant in practice: gap 9 means no payment ever
    reaches `AUTHORIZED` today, so `capture` is always rejected with `409
    INVALID_STATE_TRANSITION` before this code runs — but it would misbehave silently the moment
    gap 9 is resolved and an adapter's `capture()` returns anything other than `Success`.
25. **Webhook delivery consumes the wrong topic names for refund and settlement events.**
    `WebhookKafkaConsumer`'s `@KafkaListener` topic placeholders are plural
    (`app.kafka.topics.payments`/`.orders`/`.refunds`/`.settlements`), but `KafkaProperties`/
    `application.yaml` key them singular (`payment`/`order`/`refund`/`settlement`). Since the
    plural keys don't exist, Spring falls back to the listener's literal default strings
    (`payments.events`/`orders.events`/`refunds.events`/`settlements.events`). That happens to
    match what `OutboxPoller` actually publishes to for `payment`/`order`
    (`payments.events`/`orders.events`), but not for `refund`/`settlement`, which publish to
    `refund.events`/`settlement.events` (singular) — a mismatch that won't surface until something
    actually publishes a `REFUND`/`SETTLEMENT` outbox event, since neither is produced yet.
    Separately, `WebhookDeliverExecutor`'s `webhook.delivery.signature-header` property is missing
    the `app.` prefix every other webhook config key uses (`app.webhook.delivery.*`) — harmless
    today since it's unset and falls back to its `X-Razorpay-Signature` default, but overriding it
    through the established namespace wouldn't work.
