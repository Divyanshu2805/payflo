# API Keys

Credentials for a merchant's own backend. **Service:** merchant-service · **Controller:** `ApiKeyController` (`/v1/merchants/api-keys`)

Every endpoint acts on the authenticated caller's merchant. The path variable `{id}` is the key's row **id** (a UUID), not its `keyId`.

| Method | Path | Request | Response | Notes |
|---|---|---|---|---|
| `POST` | `/v1/merchants/api-keys` | `{ environment }` — `TEST` or `LIVE` | `201` `ApiKeyCreateResponse { id, keyId, keySecret, environment }` | `keyId` is `fp_<environment>_<random>`. **`keySecret` is shown only in this response** and stored as a bcrypt hash. |
| `GET` | `/v1/merchants/api-keys` | — | `200` `List<ApiKeyResponse { id, keyId, environment, enabled, lastUsedAt, createdAt }>` | Never includes a secret. `lastUsedAt` is always `null` today. |
| `DELETE` | `/v1/merchants/api-keys/{id}` | — | `204` | Revokes the key: `enabled = false`, row kept, gateway cache evicted. `404 APIKEY_NOT_FOUND` for an unknown id or another merchant's key. |
| `POST` | `/v1/merchants/api-keys/{id}/rotate` | — | `200` `ApiKeyCreateResponse` with a new `keySecret` | The old secret keeps working for 24 hours (`grace_period_expires_at`), so an integration can switch without downtime. `keyId` doesn't change. Rotating a revoked key fails with a `500` today ([known gaps](../known-gaps/api-behavior.md)). |

## Using a key

Send it as HTTP Basic on any endpoint:

```
Authorization: Basic base64(<keyId>:<keySecret>)
```

The gateway checks it, applies the per-key rate limit and forwards `X-Merchant-Id`, `X-Key-Id` and `X-Environment` to the service. See the [authentication flow](../architecture/flows/authentication.md) and [rate limits](idempotency-and-rate-limits.md#rate-limits).

`TEST` and `LIVE` keys behave identically today — every payment goes to the simulated acquirer either way; the environment is only forwarded.
