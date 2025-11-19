# Known gaps vs. requirements / v1 design

[← Back to docs index](README.md)

Found while syncing docs to the actual implementation (2025-10-06) — not yet triaged as intentionally
dropped vs. still planned:

1. `ORDER_RECORD` has no `idempotency_key` — the idempotent-order-creation requirement isn't backed yet.
2. `API_KEY` has no `webhook_secret_hash`.
3. `CUSTOMER` has no `gst_id`.
4. `PAYMENT` has no running `refunded_amount` total (derivable from `REFUND` rows instead).
5. `PAYMENT_TRANSITION_LOG` has no `reason` field.
6. **Secrets are stored unhashed in three places** — `AuthServiceImpl.signup` writes
   `request.password()` straight into `AppUser.passwordHash`, and both
   `ApiKeyServiceImpl.create`/`.rotate` write the raw generated secret straight into
   `ApiKey.keySecretHash`/`previousKeySecretHash`. None of it is hashed. Flagged, not fixed yet;
   commit and push proceeded as-is at the user's explicit call (2025-10-10 for the password,
   2025-10-14/26 for the API key secret), pending one shared hashing-dependency decision
   (`spring-security-crypto` vs. full `spring-boot-starter-security`) to fix all of it together.
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
   `payment/simulator/BankCallbackSimulator.processCallbacks()` is meant to poll for payments stuck
   in `AUTHORIZING` (`PaymentRepository.findByStatusAndCreatedAtBefore`) and resolve each one after
   a per-method simulated delay (`payment.simulator.methods.<METHOD>.min/max-delay-seconds`,
   `application.yaml`) by calling `PaymentService.resolveAuthorization` — but its `@Scheduled`
   annotation is commented out, and `PayFloApplication` has no `@EnableScheduling` either, so
   nothing ever invokes it. `resolveAuthorization` itself is fully implemented and correct: fires
   `AUTHORIZE_SUCCESS` (or `AUTHORIZE_FAIL`, per a configurable `payment.simulator.methods.
   <METHOD>.success-rate` and `chaos-mode` — `NORMAL`/`SLOW`/`SUCCESS`/`FAILURE`/`TIMEOUT`), then
   auto-captures on approval (fires `CAPTURE_REQUEST` → calls the adapter's `capture()` → fires
   `CAPTURE_SUCCESS`/`CAPTURE_FAIL`/`CAPTURE_PENDING`, and sets the order to `PAID`). But since
   nothing calls it automatically, and it isn't exposed via any endpoint either, a payment initiated
   today sits in `AUTHORIZING` forever — so `POST /v1/payments/{paymentId}/capture` still always
   gets rejected with `409 INVALID_STATE_TRANSITION`, same symptom as before, just with the fix
   sitting unplugged rather than nonexistent. Once wired up, note that `capture` becomes largely
   redundant in the normal flow — `resolveAuthorization`'s auto-capture already does the job that
   endpoint exists for.
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
