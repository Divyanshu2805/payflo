# Idempotency and Rate Limits

## Idempotency

Any `POST`, `PUT` or `PATCH` to a business service may send an `X-Idempotency-Key` header. `common-lib`'s `IdempotencyFilter`, registered in every business service, makes a retry with the same key safe:

- The first request claims `idempotency:<merchantId>:<key>` in Redis with an atomic `SET NX` and a 30-second in-progress marker, then runs.
- On a successful response (`< 400`, non-empty), the status and body are stored for **24 hours**. On an error the claim is deleted, so the client can retry cleanly.
- A repeat within 24 hours gets the stored status and body replayed — the operation doesn't run again.
- A repeat while the first is still running gets `409 IDEMPOTENCY_CONFLICT`.
- Requests without the header are untouched.

`POST /v1/payments` goes further: the same header becomes the payment's own `idempotency_key`, unique per merchant in the database, so even after the 24-hour window a retry returns the payment already created under that key rather than a second attempt.

The key is not tied to the request body: reusing a key with a different payload replays the first response. See [known gaps](../gaps.md).

## Rate limits

Only API-key traffic is limited, at the gateway: **200 requests per minute per key** by default (`app.rate-limit.use-case.api-key.requests-per-minute`). JWT traffic is not limited.

Every API-key response carries:

| Header | Meaning |
|---|---|
| `X-RateLimit-Limit` | The per-minute limit |
| `X-RateLimit-Remaining` | Requests left in the current window |

An over-limit request is `429 RATE_LIMIT_EXCEEDED` with `Retry-After` in seconds, and never reaches a service.

The algorithm is chosen by `app.rate-limit.method` — `fixed` (fixed window, the default), `sliding` (sorted-set sliding window), `sliding-lua` (the same, atomically in Lua) or `bucket` (token bucket in Lua). They behave differently when Redis is down: the two Lua-based limiters let traffic through, the other two fail the request. See [known gaps](../gaps.md).
