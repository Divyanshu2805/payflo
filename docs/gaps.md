# Known gaps vs. requirements / v1 design

[← Back to docs index](README.md)

Found while syncing docs to the actual implementation (2025-10-06) — not yet triaged as intentionally
dropped vs. still planned:

1. `ORDER_RECORD` has no `idempotency_key` — the idempotent-order-creation requirement isn't backed yet.
2. `API_KEY` has no `webhook_secret_hash`.
3. `CUSTOMER` has no `gst_id`.
4. `PAYMENT` has no running `refunded_amount` total (derivable from `REFUND` rows instead).
5. `PAYMENT_TRANSITION_LOG` has no `reason` field.
6. ~~Secrets are stored unhashed~~ — **partially resolved (2025-11-24):** `AuthServiceImpl.signup`
   now hashes the password with the `PasswordEncoder` (`BCryptPasswordEncoder`) bean from
   `WebSecurityConfig` before writing it to `AppUser.passwordHash`. Still open:
   `ApiKeyServiceImpl.create`/`.rotate` write the raw generated secret straight into
   `ApiKey.keySecretHash`/`previousKeySecretHash`, unhashed. Flagged, not fixed yet — commit and
   push proceeded as-is at the user's explicit call (2025-10-14/26).
7. **`OrderController`/`PaymentController` use a hardcoded `merchantId`** — each has its own fixed
   test UUID instance field instead of deriving the merchant from any caller identity, since
   there's no auth yet. Every order or payment created, fetched, cancelled, or listed currently
   belongs to/is scoped to that same merchant regardless of caller.
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
    card's DEK becomes permanently unwrappable. `POST /v1/vault/tokenize` also uses the same
    hardcoded `merchantId` pattern as the other endpoints. `VaultServiceImpl.charge` decrypts the
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
    any `AuthenticationException` to `401` with `INVALID_CREDENTIALS`. What's still open: nothing
    validates the returned JWT on later requests (see gap 13) — so the token is real, but nothing
    can actually use it yet. See [APIs](api.md) for the full current behavior.
13. **`/v1/merchants/**` now requires authentication with no way to ever provide it.**
    `WebSecurityConfig.jwtChain`'s `securityMatcher` covers `/v1/auth/**`/`/v1/merchants/**`/
    `/v1/admin/**`/`/actuator/**`/`/webhook/**`, permitting only `/v1/auth/signup`,
    `/v1/auth/login`, and `/webhook/**` — everything else in that group now requires
    `.anyRequest().authenticated()`. But there is no `JwtAuthenticationFilter` (or any other
    mechanism) anywhere in the codebase that reads a `Bearer` token and populates
    `SecurityContextHolder`, so authentication can never succeed. Net effect:
    `POST`/`GET`/`DELETE`/`POST .../rotate` under `/v1/merchants/{merchantId}/api-keys` — which
    worked (unauthenticated) before this change — now reject every request, with or without a
    valid JWT attached. `/v1/orders`, `/v1/payments`, and `/v1/vault` are unaffected (not covered
    by this `securityMatcher`, so they fall outside Spring Security's filter processing entirely
    and remain reachable, same as before). Flagged, not fixed — commit and push proceeded as-is at
    the user's explicit call (2025-11-24); a `JwtAuthenticationFilter` is the next piece needed.
