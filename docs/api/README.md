# API Reference

The contract for PayFlo's REST API. There is no generated OpenAPI spec, so these pages are the reference: when an endpoint's shape changes, update its page in the same change.

## Base URL and routing

Every call goes to the gateway — `http://localhost:8080` locally and on the kind cluster. The gateway authenticates it and forwards it, unmodified, to the service that owns the path:

| Path prefix | Owning service |
|---|---|
| `/v1/auth/**`, `/v1/merchants/**` | merchant-service |
| `/v1/orders/**`, `/v1/payments/**` | payment-service |
| `/v1/vault/**` | vault-service |
| `/webhook/**` | operations-service (a local test endpoint) |
| `/internal/**` | Never routed — service-to-service only |

A path no route owns is a 404 from the gateway.

## Conventions

**Authentication.** Every endpoint needs one of two credentials, except `POST /v1/auth/signup`, `POST /v1/auth/login`, `/webhook/**` and `/actuator/health`:

- `Authorization: Bearer <jwt>` — from `POST /v1/auth/login`, valid 100 minutes. For a merchant's staff.
- `Authorization: Basic base64(keyId:secret)` — an API key from `POST /v1/merchants/api-keys`. For a merchant's own backend.

Either works on every endpoint. See [Authentication](authentication.md).

**Tenancy.** The merchant is always the authenticated caller's — no endpoint takes a merchant id. Another merchant's order, payment or key is `404`, never `403`.

**Request bodies** are JSON and validated; a failure is `400 VALIDATION_FAILED` listing every invalid field.

**Money** is always `{ "amountUnits": 50000, "currency": "INR" }` — an integer count of the smallest unit (paise), never a decimal.

**Idempotency and rate limits.** Writes accept `X-Idempotency-Key`; API-key traffic is limited to 200 requests per minute. See [Idempotency and rate limits](idempotency-and-rate-limits.md).

**Errors** share one JSON shape from every service and from the gateway. Branch on the status and `errorCode`, never on the description. See [Errors](errors.md).

## Endpoints

| Area | Service | Page |
|---|---|---|
| Signup and login | merchant | [Authentication](authentication.md) |
| API keys — create, list, revoke, rotate | merchant | [API keys](api-keys.md) |
| Webhook endpoints — create, list, read, update, delete | merchant | [Webhook configs](webhook-configs.md) |
| Orders | payment | [Orders](orders.md) |
| Payments and capture | payment | [Payments](payments.md) |
| Card tokenization | vault | [Vault](vault.md) |
| Service-to-service API | all | [Internal API](internal.md) |

## Reference

- [Mock acquirer](mock-acquirer.md) — the test values that make a payment fail, and how the simulated bank decides.
- [Idempotency and rate limits](idempotency-and-rate-limits.md).
- [Errors](errors.md) — the error shape and every exception → status mapping.
- [API behavior worth knowing](../gaps.md) — responses that are easy to mistake for bugs.
