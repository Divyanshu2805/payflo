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
9. ~~`BankCallbackSimulator` not scheduled~~ — **resolved (2025-11-24):** `processCallbacks()`'s
   `@Scheduled` is uncommented and `PayFloApplication` now carries `@EnableScheduling`, so a
   payment that reaches `AUTHORIZING` resolves on its own — `resolveAuthorization` fires
   `AUTHORIZE_SUCCESS`/`AUTHORIZE_FAIL` per the configured success rate, then auto-captures on
   approval and marks the order `PAID` — within `payment.simulator.poll-interval-ms` of its
   per-method simulated delay, no manual step needed. One residual note: `POST
   /v1/payments/{paymentId}/capture` is now *more* redundant than before rather than less — by the
   time a caller could invoke it, auto-capture has usually already moved the payment past
   `AUTHORIZED` (so the manual call 409s as "already captured") or it's still `AUTHORIZING` (409s as
   before); there's now only a narrow timing window where a manual capture would actually apply.
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
    validates the returned JWT on later requests — `WebSecurityConfig.jwtChain` still does
    `anyRequest().permitAll()` — so the token is real but nothing checks it yet. See
    [APIs](api.md) for the full current behavior.
