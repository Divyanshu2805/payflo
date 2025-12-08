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
   password with the `PasswordEncoder` (`BCryptPasswordEncoder`) bean from `WebSecurityConfig`
   before writing it to `AppUser.passwordHash`, and `ApiKeyServiceImpl.create`/`.rotate` now do the
   same for the generated API key secret — the raw value is only ever returned once in the
   response (`ApiKeyCreateResponse`, built directly from the raw string rather than mapped off the
   now-hashed entity field); `ApiKey.keySecretHash`/`previousKeySecretHash` store only the hash.
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
    actor`), since there's no auth context yet to attribute a transition to a specific merchant,
    customer, or admin. `PaymentTransitionLogRepository` is also currently a bare
    `JpaRepository` — no custom finder methods yet (nothing reads the log back out through the API
    today).
11. **`vault.encryption.master-key` has a hardcoded dev-only default** in `application.yaml`
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
    (2025-11-24):** `merchant/security/JwtAuthenticationFilter` now exists — a `OncePerRequestFilter`
    that reads the `Authorization: Bearer <token>` header, verifies it via `JwtUtil`, populates
    `SecurityContextHolder` (so `.anyRequest().authenticated()` can actually be satisfied), and sets
    the resolved merchant on `merchant/security/MerchantContext` (a `@RequestScope` bean) from the
    token's `merchant_id` claim. Delegates auth-failure handling to Spring's own
    `HandlerExceptionResolver` rather than a bespoke response, so a bad/expired token flows through
    the normal exception-handling path. `WebSecurityConfig`'s protected-route matcher was also
    widened to include `/v1/orders/**`/`/v1/payments/**`/`/v1/vault/**` — those now require
    authentication too (a real behavior change; they were previously fully open), because
    `MerchantContext` needs a JWT to populate from regardless of which controller uses it (see
    gap 7). Every merchant-scoped controller (`OrderController`, `PaymentController`,
    `VaultController`, `ApiKeyController`) now injects `MerchantContext` instead of a hardcoded or
    path-variable merchant id.
