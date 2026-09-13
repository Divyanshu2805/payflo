# Not Yet Built

Known missing features and open issues, grouped by area. Items marked *(from reading the code)* were found while writing these docs and haven't been reproduced against a running system.

## Security

- **No service-to-service authentication.** `/internal/**` has none, and every business service trusts `X-Merchant-Id` from any caller (`app.security.trust-inbound-headers` defaults to `true`). There is no mTLS, service token or `NetworkPolicy` — see [constraints](constraints-and-trade-offs.md#trust-between-services-is-by-reachability).
- **The gateway doesn't strip client identity headers.** It overwrites the headers it sets but passes the rest through: on a JWT request a client-sent `X-Key-Id` reaches the service (and ends up in `created_by`), and on a public route every identity header does. *(from reading the code)*
- **A card token isn't checked against the paying merchant.** `POST /internal/vault/charge` finds the card by token alone — the request carries no merchant id — so a merchant who learned another merchant's token could charge that card. *(from reading the code)*
- **Login reveals whether an email is registered.** An unknown email is `404 USER_NOT_FOUND`, a wrong password `400 INVALID_CREDENTIALS`. The monolith made both a uniform `401`.
- **No roles or permissions within a merchant.** Any authenticated user or API key can do anything that merchant can; `AppUser.role` travels in the JWT as `X-User-Role` but nothing reads it.
- **Development secrets are committed.** The JWT key, vault master key and webhook encryption key default to fixed values in `config-repo/`, and `k8s/secrets.env.example` holds the same. Losing or rotating the master key without re-encryption makes every vaulted card unreadable. A real deployment needs a secret store.
- **The decrypted card number lives on the heap as a `String`.** vault-service zeroes the byte array after use, but the `String` copy can't be zeroed.
- **`api_key.last_used_at` is never written.**
- **Failed authentication attempts aren't throttled** — the rate limit applies only after a key verifies.

## API

- **No read endpoints for payments, deliveries or settlements.** A merchant learns a payment's outcome only from a webhook; there is no way to list webhook deliveries, replay a dead-lettered one, or see settlements.
- **A webhook config can't be disabled** without deleting it.
- **The idempotency key isn't tied to the request body** — reusing a key with a different payload replays the first response. And a malformed stored value would fall through to a parse error, since `replay` doesn't return after reporting it. *(from reading the code)*
- **The four rate limiters differ.** The Lua-based two fail open when Redis is down; `fixed` (the default) and `sliding` don't. `fixed` sets `INCR` and `EXPIRE` separately, so a crash between them leaves a counter that never expires, and `sliding` checks and adds separately, so concurrent requests can both pass at the limit.
- **Rotating a revoked key is a `500`** — it throws a bare `RuntimeException`.

## Payments

- **Refunds aren't built.** The `refund` table, `RefundStatus`, the refund topic and the `REFUND_*` transitions exist; no service, endpoint or event does.
- **Orders never expire and can't be cancelled.** `expires_at` is stored but nothing acts on it, and `CANCEL` / `CAPTURE_TIMEOUT` are never fired.
- **`WALLET` has no adapter or processor.**
- **Capture always succeeds** — every adapter's `capture()` returns success unconditionally.
- **The transition log's `actor` is always `SYSTEM`**, and there is no `reason` column (the monolith had one).
- **A failed outbox row is never retried** after its third attempt.

## Settlement

- **The settlement row's refund amount is never set, though its columns are `NOT NULL`**, so saving a settlement should fail and roll that merchant's run back. Settlement has not been verified end to end. *(from reading the code)*
- **Remote calls run inside the settlement transaction.** `processForMerchant` and `resolveTransfer` call payment- and merchant-service while holding a database transaction — the pattern the payment saga avoids.
- **`mark-settled` bypasses the state machine.** It sets `SETTLED` directly, without checking the payment is `CAPTURED` and without a transition-log row.
- **The payout simulator always succeeds**, so the `FAILED` callback branch never runs and there is no chaos mode for payouts.
- **The `SETTLEMENT_*` payload puts the whole `Settlement` object under `settlementId`**, rather than its id. *(from reading the code)*
- **Refunds aren't deducted**, since refunds don't exist.

## Ported from the monolith

The monolith has these; the services don't yet:

- `POST /v1/auth/refresh` and `POST /v1/auth/logout` — refresh tokens weren't carried over.
- `GET /v1/orders/{orderId}`, `POST /v1/orders/{orderId}/cancel` and `GET /v1/orders/{orderId}/payments` — payment-service's `OrderService` implements all three, but no controller routes them.
- The wallet payment method.

## Platform

- **No schema migrations** — `ddl-auto: update` across four databases.
- **No automated tests of business logic** — each module has only `contextLoads`. See [testing](../practices/testing.md).
- **No observability** — no tracing, metrics or dashboards — and **no load tests**.
- **Kubernetes is local only** — one replica per service, a NodePort instead of an Ingress, no autoscaling, images loaded into kind rather than pushed by CI, and no CI pipeline at all.
- **Two property names for the same Kafka topics** — see [the pitfall](../practices/gotchas/microservices.md#two-property-names-for-the-same-kafka-topic).
- **Not built at all:** merchant KYC (every merchant stays `PENDING_KYC`), analytics dashboards, and the `@MaskedCard` log filter from the requirements.
