# API Reference

The contract for PayFlo's REST API. The machine-readable contract is [`openapi.yaml`](openapi.yaml); these pages explain the behaviour behind each endpoint. When an endpoint's shape changes, update its page and the spec in the same change.

## Base URL and routing

Every call goes to the gateway — `http://localhost:8080` locally and on the kind cluster. The gateway authenticates it and forwards it, unmodified, to the service that owns the path:

| Path prefix | Owning service |
|---|---|
| `/v1/auth/**`, `/v1/merchants/**`, `/v1/admin/merchants/**`, `/v1/admin/audit-log/**` | merchant-service |
| `/v1/orders/**`, `/v1/payments/**`, `/v1/refunds/**`, `/v1/analytics/**` | payment-service |
| `/v1/vault/**` | vault-service |
| `/v1/settlements/**`, `/v1/webhook-deliveries/**`, `/v1/admin/settlements/**` | operations-service |
| `/webhook/**` | operations-service (a local test endpoint) |
| `/internal/**` | Never routed — service-to-service only |

A path no route owns is a 404 from the gateway.

## Conventions

**Authentication.** Every endpoint needs one of two credentials, except `POST /v1/auth/signup`, `POST /v1/auth/login`, `POST /v1/auth/refresh` and `/webhook/**`:

- `Authorization: Bearer <jwt>` — from `POST /v1/auth/login`, valid 100 minutes (renewable with a refresh token). For a merchant's staff.
- `Authorization: Basic base64(keyId:secret)` — an API key from `POST /v1/merchants/api-keys`. For a merchant's own backend.

Either works on the orders, payments, refunds, webhooks, settlements and API-key endpoints. The merchant's own profile, payout account, KYC, users and password are for a dashboard login only, and a `TEAM` member's login is read-only. See [Authentication](authentication.md) and [Merchant account](merchant-account.md).

The [admin API](admin.md) (`/v1/admin/**`) is the one exception: it takes `X-Admin-Key`, the platform operator's key, and never a merchant credential.

**Tenancy.** The merchant is always the authenticated caller's — no endpoint takes a merchant id. Another merchant's order, payment or key is `404`, never `403`.

**Request bodies** are JSON and validated; a failure is `400 VALIDATION_FAILED` listing every invalid field.

**Lists** (`GET /v1/orders`, `/v1/payments`, `/v1/refunds`, `/v1/settlements`, `/v1/webhook-deliveries`) are newest first and paged: `?page=0&size=20` (size 1–100), optionally filtered by `status`. They answer `{ items, page, size, hasNext }` — no total count, since counting a merchant's whole history is the expensive part. Walk the list while `hasNext` is true.

**Money** is always `{ "amountUnits": 50000, "currency": "INR" }` — an integer count of the smallest unit (paise), never a decimal.

**Idempotency and rate limits.** Writes accept `X-Idempotency-Key`; API-key traffic is limited to 200 requests per minute. See [Idempotency and rate limits](idempotency-and-rate-limits.md).

**Errors** share one JSON shape from every service and from the gateway. Branch on the status and `errorCode`, never on the description. See [Errors](errors.md).

## OpenAPI and Postman

| File | What it is |
|---|---|
| [`openapi.yaml`](openapi.yaml) | The public API as OpenAPI 3.0: every path, parameter, request and response, the three credentials, and the error shape. `/internal/**` is not in it (see [Internal API](internal.md)) |
| [`payflo.postman_collection.json`](payflo.postman_collection.json) | The same API as a walkthrough to import into Postman: seven folders in the order a merchant meets them, each request storing the ids and credentials the next ones need. Its defaults are the [demo](../local-development/demo-and-dashboard.md)'s development values |
| [`check_openapi.py`](check_openapi.py) | Fails when the spec and the controllers disagree about which endpoints exist |

**Swagger UI.** With the [dashboard](../local-development/demo-and-dashboard.md) running, <http://localhost:5173/docs.html> renders the spec, and "Try it out" calls the real API. Any other OpenAPI viewer can read the file directly.

**The spec is written by hand, and checked.** Generating it would mean a documentation library and annotations in four services plus a way to merge four specs behind the gateway; one file kept honest by a check is simpler. `python docs/api/check_openapi.py` (CI runs it before the build) reads every public `@…Mapping` in the controllers and fails if one is missing from the spec, or the spec names one that no longer exists. It compares paths and methods, not field-by-field shapes: a changed request or response record still has to be changed here by hand.

## Endpoints

| Area | Service | Page |
|---|---|---|
| Signup, login, refresh, logout, password | merchant | [Authentication](authentication.md) |
| Profile, payout account, KYC, team users | merchant | [Merchant account](merchant-account.md) |
| API keys — create, list, revoke, rotate | merchant | [API keys](api-keys.md) |
| Webhook endpoints — create, list, read, update, rotate secret, delete | merchant | [Webhook configs](webhook-configs.md) |
| Webhook deliveries — list, read, replay | operations | [Webhook deliveries](webhook-deliveries.md) |
| Orders — create, read, list, cancel | payment | [Orders](orders.md) |
| Payments — pay, read, list, capture | payment | [Payments](payments.md) |
| Analytics — live dashboard, historical report | payment | [Analytics](analytics.md) |
| Refunds | payment | [Refunds](refunds.md) |
| Settlements (payouts) | operations | [Settlements](settlements.md) |
| Audit log — a merchant's own, newest first | merchant | [Audit log](audit-log.md) |
| Platform operator — suspend a merchant, run a settlement, read every audit entry (the admin key, not a merchant credential) | merchant, operations | [Admin API](admin.md) |
| Card tokenization | vault | [Vault](vault.md) |
| Service-to-service API | all | [Internal API](internal.md) |

## Reference

- [Mock acquirer](mock-acquirer.md) — the test values that make a payment fail, and how the simulated bank decides.
- [Idempotency and rate limits](idempotency-and-rate-limits.md).
- [Errors](errors.md) — the error shape and every exception → status mapping.
- [API behavior worth knowing](behavior.md) — responses that are easy to mistake for bugs.
