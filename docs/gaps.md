# Known gaps vs. requirements / v1 design

[← Back to docs index](README.md)

Found while syncing docs to the actual implementation (2025-10-06) — not yet triaged as intentionally
dropped vs. still planned:

1. `ORDER_RECORD` has no `idempotency_key` — the idempotent-order-creation requirement isn't backed yet.
2. `API_KEY` has no `webhook_secret_hash`.
3. `CUSTOMER` has no `gst_id`.
4. `PAYMENT` has no running `refunded_amount` total (derivable from `REFUND` rows instead).
5. `PAYMENT_TRANSITION_LOG` has no `reason` field.
6. **Secrets are stored unhashed in two places** — `AuthServiceImpl.signup` writes
   `request.password()` straight into `AppUser.passwordHash`, and `ApiKeyServiceImpl.create` writes
   the raw generated secret straight into `ApiKey.keySecretHash`. Neither is hashed. Flagged, not
   fixed yet; commit and push proceeded as-is at the user's explicit call (2025-10-10 for the
   password, 2025-10-14 for the API key secret), pending one shared hashing-dependency decision
   (`spring-security-crypto` vs. full `spring-boot-starter-security`) to fix both together.
