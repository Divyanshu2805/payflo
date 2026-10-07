# Idempotency and Rate Limits

## Idempotency

Any `POST`, `PUT` or `PATCH` to a business service may send an `X-Idempotency-Key` header. `common-lib`'s `IdempotencyFilter`, registered in every business service, makes a retry with the same key safe:

- The first request claims `idempotency:<merchantId>:<method>:<path>:<key>` in Redis with an atomic `SET NX` and a 30-second in-progress marker, then runs. The merchant, method and path are part of the key, so a stored response is only ever replayed to the merchant and endpoint it was created for.
- On a successful response (`< 400`, non-empty), the status and body are stored for **24 hours**. On an error the claim is deleted, so the client can retry cleanly.
- A repeat within 24 hours gets the stored status and body replayed — the operation doesn't run again.
- A repeat while the first is still running gets `409 IDEMPOTENCY_CONFLICT`.
- **The key is bound to the request.** Each request is fingerprinted — a SHA-256 of its method, path, query string and body — and stored with the key. The same key arriving with a different request, whether the first has finished or is still running, is refused with **`422 IDEMPOTENCY_KEY_REUSED`**: replaying the first answer would answer a question the caller didn't ask. A retry must be byte-for-byte the same request; use a new key for a new one.
- Requests without the header are untouched. So are requests with no authenticated merchant — the gateway itself and the public signup and login routes never cache a response, since there is no merchant to scope it to. Without a key a payment still can't be duplicated: an order accepts a new payment only when its earlier attempts all failed ([`ORDER_PAYMENT_IN_PROGRESS`](payments.md)).

`POST /v1/payments` goes further: the same header becomes the payment's own `idempotency_key`, unique per merchant in the database, so even after the 24-hour window a retry returns the payment already created under that key rather than a second attempt.

The same holds one layer down for the three endpoints that also keep the key in the database — orders, payments and refunds, each with a unique `(merchant_id, idempotency_key)` index: an order found under a key must have the same amount, receipt and notes, a payment the same order and method, and a refund the same payment and amount, or the request is `422 IDEMPOTENCY_KEY_REUSED`. This is what protects a retry after the 24-hour window, after Redis dropped the entry, or while Redis was down (the [crash tests](../reliability/crash-and-outage-tests.md#what-this-found) found orders were the one case that relied on Redis alone, now closed), and it makes two simultaneous requests with one key resolve to one record. A key longer than 100 characters on an order is `400 IDEMPOTENCY_KEY_TOO_LONG`.

### What is deliberately not stored or hashed

- **A response that carries a secret shown only once is never stored.** Creating or rotating an API key, and creating a webhook or rotating its secret (`/v1/merchants/api-keys`, `…/{id}/rotate`, `/v1/merchants/webhooks`, `…/{id}/rotate-secret`), would otherwise keep the secret in Redis in plaintext for 24 hours. The filter stores only a marker that the request ran, so a retry with the same key is **`409 IDEMPOTENT_RESPONSE_NOT_REPLAYABLE`** and the secret is not repeated. Use a new key to create another.
- **A body that holds card data or a password is not part of the fingerprint.** On `/v1/vault/**`, `/v1/auth/**`, `/v1/merchants/users` and `/v1/merchants/me/settlement-bank` the fingerprint covers method, path and query only: a hash of a card number or a password is small enough to guess offline, and must not be kept in Redis. A retry there replays by key alone, as before.
- A request body over 1 MB is not fingerprinted, and so its key is not honoured.

### Which writes are protected

Every `POST`, `PUT` and `PATCH` carrying a key goes through the filter. The ones that create something and could otherwise be duplicated — orders, payments, refunds, tokenize, API keys, webhooks, users — are the reason for it. The rest don't need it: signup (`409` on a duplicate email), login (stateless), refresh (single-use), cancel and capture (state-guarded: a second one is `400`/`409`), the `PUT`s and `DELETE`s (the same result however often they run).

## Rate limits

Three limits, all at the gateway, all answered `429 RATE_LIMIT_EXCEEDED` with `Retry-After`:

| Limit | Default | Setting |
|---|---|---|
| Per API key | 200 / minute | `API_KEY_RATE_LIMIT_PER_MINUTE` |
| Per merchant, for JWT (dashboard) traffic | 600 / minute | `JWT_RATE_LIMIT_PER_MINUTE` |
| Per client address, on signup and login | 120 / minute | `PUBLIC_AUTH_RATE_LIMIT_PER_MINUTE` |

And failed authentications are limited too: an address with 30 in a minute (`MAX_FAILED_AUTH_PER_MINUTE`) is refused outright for the rest of that minute, before any credential is checked — the per-key limit above only applies once a key has verified. Behind a proxy or load balancer, set `CLIENT_IP_HEADER` (for example `X-Forwarded-For`) so these see clients rather than the proxy; leave it empty when clients connect directly, since a client could otherwise choose its own address.

Every API-key response carries:

| Header | Meaning |
|---|---|
| `X-RateLimit-Limit` | The per-minute limit |
| `X-RateLimit-Remaining` | Requests left in the current window |

An over-limit request is `429 RATE_LIMIT_EXCEEDED` with `Retry-After` in seconds, and never reaches a service.

The algorithm is chosen by `app.rate-limit.method`: `fixed` (fixed window, the default), `sliding-lua` (a sliding window over a sorted set, checked and added atomically in Lua; `sliding` is an older name for the same limiter) or `bucket` (a token bucket in Lua). All of them **fail open**: if Redis can't be reached the request is let through, on the reasoning that an outage of the limiter should not become an outage of the API.

## Velocity limits and card testing

The rate limits above are about *volume*. **Card testing** is a different abuse that a modest volume can still do: someone with a merchant's API key (their own, or a stolen one) runs a list of stolen card numbers through it — tokenize each, charge a small amount — to learn which are live. Three rules stop that, and none of them is meant to bother an honest merchant. Their answers use their own error codes, so a client can tell them from the ordinary `RATE_LIMIT_EXCEEDED`.

| Rule | Where | Refuses with | Default |
|---|---|---|---|
| **Cards tokenized per merchant** | vault-service, `TokenizeVelocityGuard`, before anything is encrypted or stored | `429 CARD_TOKENIZATION_LIMIT_EXCEEDED` with `Retry-After` | 30 a minute **and** 600 an hour |
| **A merchant whose card payments mostly fail** | payment-service, `CardVelocityGuard`, before a card payment is recorded | `429 CARD_TESTING_SUSPECTED` with `Retry-After` | once **20** card payments have failed in the last 10 minutes *and* they are at least **half** of that merchant's card payments |
| **Card payments per order** | payment-service, inside the order's lock | `400 ORDER_CARD_ATTEMPTS_EXCEEDED` | 5 per order; the next attempt needs a new order |

- **Why a share and a count.** An honest merchant has declines — the simulated bank declines about one card payment in ten, and a real one is no different — so a count alone would eventually refuse every busy merchant. A bot fails nearly all of them, so it crosses both lines within a few attempts. Both must be reached: 5 declines out of 5 is a new merchant with a bad day, not yet testing, and 20 out of 400 is just volume.
- **What counts as a failure.** The acquirer or bank refused the card (`CARD_DECLINED`, `CARD_EXPIRED`, the bank's asynchronous decline). Not PayFlo's own trouble reaching vault-service (`VAULT_CHARGE_FAILED`, an unreachable vault): that isn't the card's fault, and counting it would turn an outage into a lockout.
- **What a refused merchant can still do.** Only *card* payments are refused. UPI, net banking and wallet payments, orders, refunds and everything else carry on, and other merchants are never affected. The refusal ends by itself when the window does (`Retry-After` says when); an order that was refused is still payable another way.
- **What is not counted.** A replay of the same request (same `X-Idempotency-Key`) is the same payment, not a new attempt. Tokenizations rejected for an invalid card number (Luhn) never reach the guard, so a bot that feeds only invalid numbers is stopped by validation, not by this limit.
- **How it is stored.** Counts live in one Redis hash per merchant and window (`velocity:card:<merchantId>`, `velocity:tokenize:minute|hour:<merchantId>`), so attempts and failures always cover the same stretch of time. Like the rate limiters it **fails open**: if Redis can't be reached nothing is counted or refused.
- **Seeing it.** Each refusal increments `payflo.velocity.refused` with a `rule` tag (`tokenize-per-minute`, `tokenize-per-hour`, `card-testing`, `order-card-attempts`) and is logged at `WARN`, so it can be graphed and alerted on.

Settings: `vault.velocity.*` in `config-repo/vault-service.yaml` and `payment.velocity.card.*` in `config-repo/payment-service.yaml`; each has an `enabled` switch (`VAULT_VELOCITY_ENABLED`, `CARD_VELOCITY_ENABLED`) for a test that fails a lot of card payments on purpose. The [load test](../load-testing/README.md) is unaffected: it tokenizes a card once per thread and its declines are about one in ten.
