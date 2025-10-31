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
8. **`PaymentAdapter` implementations are stubs** — `CardPaymentAdapter`, `NetBankingAdapter`, and
   `UpiPaymentAdapter` implement the interface and are wired into `PaymentGatewayRouter` via
   `PaymentAdapterConfig`, but each `initiate()` body is a `// TODO` returning `null` — no real
   (or mock) acquirer integration exists yet. The "Mock acquirer" requirement is not satisfied.
9. **`Payment.idempotencyKey` is a fresh random value every call, never checked** —
   `PaymentServiceImpl.initiate` generates `UUID.randomUUID().toString()` per request instead of
   accepting/deriving a caller-supplied key and looking up an existing `Payment` by it, so retrying
   a payment-initiation request creates a duplicate `Payment` row rather than returning the
   original.
