# API Reference

Every call goes to the gateway: `http://localhost:8080` locally and on the kind cluster. The contract is [`openapi.yaml`](openapi.yaml) (53 endpoints, checked against the controllers in CI by [`check_openapi.py`](check_openapi.py)). [`payflo.postman_collection.json`](payflo.postman_collection.json) walks the same API in the order a merchant meets it. With the dashboard running, <http://localhost:5173/docs.html> renders the spec in Swagger UI with "Try it out".

- [Conventions](#conventions)
- [Authentication](#authentication)
- [Merchant account](#merchant-account)
- [API keys](#api-keys)
- [Orders](#orders)
- [Payments](#payments)
- [Vault](#vault)
- [Refunds](#refunds)
- [Settlements](#settlements)
- [Analytics](#analytics)
- [Webhooks](#webhooks)
- [Audit log](#audit-log)
- [Admin API](#admin-api)
- [Idempotency](#idempotency)
- [Rate and velocity limits](#rate-and-velocity-limits)
- [Errors](#errors)
- [Test values](#test-values)
- [Internal API](#internal-api)

## Conventions

| | |
|---|---|
| **Credentials** | `Authorization: Bearer <jwt>` for a merchant's staff, or `Authorization: Basic base64(keyId:secret)` for a merchant's backend. Both work on every merchant endpoint, except the dashboard-only actions (profile, payout account, KYC, users, password). Public: signup, login, refresh, `/webhook/**` |
| **Tenancy** | The merchant is always the authenticated caller. No endpoint takes a merchant id; another merchant's resource is `404` |
| **Money** | `{ "amountUnits": 50000, "currency": "INR" }`: an integer in the smallest unit (paise) |
| **Lists** | Newest first, `?page=0&size=20` (1–100), optional `status` filter. Answer `{ items, page, size, hasNext }` |
| **Validation** | `400 VALIDATION_FAILED` with every invalid field in `fieldErrors` |
| **Asynchronous outcomes** | A payment comes back `AUTHORIZING` and a refund `PENDING`; the outcome follows in seconds. Poll `GET` or subscribe to webhooks |
| **Declines** | A declined payment is still `201`, with `status: FAILED` and an `errorCode` |

## Authentication

merchant-service · `/v1/auth`

| Method | Path | Body | Response |
|---|---|---|---|
| `POST` | `/v1/auth/signup` | `{ name, email, password, businessName?, businessType? }` | `201` merchant (`PENDING_KYC`) and its `OWNER` user |
| `POST` | `/v1/auth/login` | `{ email, password }` | `200 { accessToken, refreshToken, expiresInSeconds }` |
| `POST` | `/v1/auth/refresh` | `{ refreshToken }` | `200` a new access and refresh token; the old refresh token is used up |
| `POST` | `/v1/auth/logout` | `{ refreshToken? }` | `204`; the access token is revoked at the gateway |
| `POST` | `/v1/auth/password` | `{ currentPassword, newPassword }` | `204`; ends every session of the user |

| Rule | Behaviour |
|---|---|
| Access token | JWT with `merchant_id`, `role` and its own id, valid 100 minutes |
| Refresh token | Random, single use, valid 7 days, stored hashed |
| Unknown email vs wrong password | Same `401 INVALID_CREDENTIALS`, same timing |
| Lockout | 10 wrong passwords in 15 minutes: `429` with `Retry-After` |
| Signup for a registered email | The same `201` shape; nothing is created or changed, so signup reveals no accounts |
| Suspended merchant | `403 MERCHANT_SUSPENDED` once the password is right |

## Merchant account

merchant-service · `/v1/merchants/me`, `/v1/merchants/users`. Changes need a dashboard login; an API key gets `403 DASHBOARD_LOGIN_REQUIRED`.

| Method | Path | Who | Notes |
|---|---|---|---|
| `GET` | `/v1/merchants/me` | any | PAN and payout account returned masked |
| `PUT` | `/v1/merchants/me` | `OWNER`, `ADMIN` | Only the fields sent change |
| `PUT` | `/v1/merchants/me/settlement-bank` | `OWNER` + password | `{ accountNumber, ifsc, accountHolderName, currentPassword }`; stored encrypted |
| `POST` | `/v1/merchants/me/kyc` | `OWNER` | Simulated: a complete profile and payout account move `PENDING_KYC` → `ACTIVE`. PAN `AAAAA0000A` is rejected (`KYC_REJECTED`) |
| `GET` | `/v1/merchants/users` | `OWNER`, `ADMIN` | |
| `POST` | `/v1/merchants/users` | `OWNER` | `{ email, password, role }`, role `ADMIN` or `TEAM` |
| `PUT` | `/v1/merchants/users/{userId}/role` | `OWNER` | Ends that user's sessions |
| `DELETE` | `/v1/merchants/users/{userId}` | `OWNER` | The owner can't be removed |

| Role | Can do |
|---|---|
| `OWNER` | Everything |
| `ADMIN` | Everything except payout account, KYC and users |
| `TEAM` | Read only; any write is `403 ROLE_FORBIDDEN` at the gateway |

## API keys

merchant-service · `/v1/merchants/api-keys`. The path id is the key's row `id`, not its `keyId`.

| Method | Path | Notes |
|---|---|---|
| `POST` | `/v1/merchants/api-keys` | `{ environment: TEST \| LIVE }` → `{ id, keyId, keySecret }`. The secret is shown only here |
| `GET` | `/v1/merchants/api-keys` | Never includes a secret |
| `DELETE` | `/v1/merchants/api-keys/{keyId}` | Revokes at once; the gateway cache is evicted |
| `POST` | `/v1/merchants/api-keys/{keyId}/rotate` | `{ gracePeriodHours: 0–24 }` (default 24): the old secret works until then |

## Orders

payment-service · `/v1/orders`

| Method | Path | Notes |
|---|---|---|
| `POST` | `/v1/orders` | `{ amount, receipt?, notes?, expiresAt?, customer? }`. Amount 1 to 500,000,000 paise, INR. Receipt unique per merchant. Publishes `ORDER_CREATED` |
| `GET` | `/v1/orders` | `?status=CREATED \| ATTEMPTED \| PAID \| CANCELLED \| EXPIRED` |
| `GET` | `/v1/orders/{orderId}` | |
| `GET` | `/v1/orders/{orderId}/payments` | Every attempt, oldest first |
| `POST` | `/v1/orders/{orderId}/cancel` | Only unpaid, with no payment in flight |

An order expires 15 minutes after creation unless `expiresAt` says otherwise. A customer block with an email is found or created by merchant and email.

## Payments

payment-service · `/v1/payments`

| Method | Path | Notes |
|---|---|---|
| `POST` | `/v1/payments` | `{ orderId, method, methodDetails }` → `201`, usually `AUTHORIZING` |
| `GET` | `/v1/payments` | `?status=&orderId=` |
| `GET` | `/v1/payments/{paymentId}` | Poll the outcome |
| `POST` | `/v1/payments/{paymentId}/capture` | Captures an `AUTHORIZED` payment; a refusal returns it `AUTHORIZED` with `CAPTURE_DECLINED` |

| `method` | `methodDetails` |
|---|---|
| `CARD` | `{ "token": "tok_…" }` from the vault |
| `UPI` | `{ "vpa": "name@bank" }` |
| `NETBANKING` | `{ "bank": "<code>" }` |
| `WALLET` | `{ "wallet": "PAYTM" }` |

| Rule | Answer |
|---|---|
| Order not `CREATED` or `ATTEMPTED` | `400 ORDER_NOT_PAYABLE` |
| Order past `expiresAt` | `400 ORDER_EXPIRED` |
| Another payment for the order in flight or done | `400 ORDER_PAYMENT_IN_PROGRESS` |
| Missing or malformed details | `400 INVALID_PAYMENT_DETAILS` |
| Sixth card payment on one order | `400 ORDER_CARD_ATTEMPTS_EXCEEDED` |
| Card testing suspected | `429 CARD_TESTING_SUSPECTED` (cards only) |

| `errorCode` on a `FAILED` payment | Meaning |
|---|---|
| `CARD_DECLINED`, `CARD_EXPIRED`, `UPI_REJECTED`, `BANK_REJECTED`, `WALLET_REJECTED` | A [test value](#test-values) |
| `SIM_BANK_ERROR_CODE` | The simulated bank declined it |
| `CARD_TOKEN_INVALID` | Unknown, revoked or another merchant's token |
| `PAYMENT_GATEWAY_ROUTER_UNREACHABLE` | The processor couldn't be reached; nothing was charged and the order stays payable |
| `PAYMENT_AUTHORIZATION_TIMEOUT` | No answer from the bank within 15 minutes |
| `CAPTURE_TIMEOUT` | Authorized but not captured within 60 minutes (`AUTH_EXPIRED`) |

## Vault

vault-service · `POST /v1/vault/tokenize`

| Field | Rule |
|---|---|
| `pan` | 13–19 digits, Luhn-valid; never returned |
| `cvv` | 3–4 digits; validated, never stored |
| `expiryMonth`, `expiryYear` | Not in the past |
| `cardHolderName` | At least 3 characters |
| `customerId` | Optional |

Returns `201 { token, lastFour, brand, expiryMonth, expiryYear }`. A merchant may tokenize 30 cards a minute and 600 an hour.

## Refunds

payment-service

| Method | Path | Notes |
|---|---|---|
| `POST` | `/v1/payments/{paymentId}/refunds` | `{ amountUnits?, notes? }`; omit the amount to refund what is left. Starts `PENDING` |
| `GET` | `/v1/payments/{paymentId}/refunds` | Oldest first |
| `GET` | `/v1/refunds` | `?status=PENDING \| PROCESSED \| FAILED` |
| `GET` | `/v1/refunds/{refundId}` | |

| Rule | Answer |
|---|---|
| Payment not `CAPTURED` or `PARTIALLY_REFUNDED` | `400 PAYMENT_NOT_REFUNDABLE` |
| Payment already paid out | `400 PAYMENT_ALREADY_SETTLED` |
| More than what is left (pending refunds count) | `400 REFUND_EXCEEDS_PAYMENT` |

Approved: `PROCESSED`, and the payment becomes `REFUNDED` once refunds reach its amount. Declined: `FAILED` (`SIM_REFUND_DECLINED`), and the payment returns to `CAPTURED` unless other refunds stand.

## Settlements

operations-service · `/v1/settlements`

| Method | Path | Notes |
|---|---|---|
| `GET` | `/v1/settlements` | `?status=INITIATED \| TRANSFER_PENDING \| PROCESSED \| FAILED` |
| `GET` | `/v1/settlements/{settlementId}` | `{ grossAmount, refundAmount, feeAmount, gstAmount, netAmount, status, bankReference, failureReason }` |
| `GET` | `/v1/settlements/{settlementId}/payments` | The payment ids it covers |

`netAmount = grossAmount − refundAmount − feeAmount − gstAmount`. The fee is 2% of what the merchant kept and GST is 18% of the fee. A merchant is settled once `ACTIVE` with a payout account.

## Analytics

payment-service · read-only, every role, never cached

| Method | Path | Response |
|---|---|---|
| `GET` | `/v1/analytics/dashboard` | Today, the last seven days day by day, and captured payments by method |
| `GET` | `/v1/analytics/report` | `?from=&to=&granularity=DAY \| WEEK \| MONTH`, up to 366 days (default the last 30) |

Each figure holds `grossAmountUnits` and `capturedCount` (by capture time), `refundedAmountUnits` (by processing time), `netAmountUnits`, `paymentsCreated`, `failedCount` and `successRate` = captured ÷ (captured + failed). Empty days are zeros.

## Webhooks

### Endpoints

merchant-service · `/v1/merchants/webhooks`

| Method | Path | Notes |
|---|---|---|
| `POST` | `/v1/merchants/webhooks` | `{ targetUrl, eventTypes?, enabled? }` → includes `webhookSecret`, shown only here |
| `GET` | `/v1/merchants/webhooks` | Without secrets |
| `GET` | `/v1/merchants/webhooks/{id}` | |
| `PUT` | `/v1/merchants/webhooks/{id}` | Change the URL or events; `enabled: false` pauses |
| `POST` | `/v1/merchants/webhooks/{id}/rotate-secret` | The next attempt is signed with the new secret |
| `DELETE` | `/v1/merchants/webhooks/{id}` | |

`targetUrl` must be `https`, carry no credentials and resolve only to public addresses (`400 WEBHOOK_URL_NOT_ALLOWED`); development allows loopback and plain `http` with `WEBHOOK_ALLOW_PRIVATE_TARGETS=true`. `eventTypes` is a comma-separated list, or blank or `ALL` for everything.

| Event | Sent when |
|---|---|
| `ORDER_CREATED`, `ORDER_CANCELLED`, `ORDER_EXPIRED` | An order is created, cancelled or expires unpaid |
| `PAYMENT_CREATED` | A payment attempt is recorded |
| `PAYMENT_STATUS_CHANGED` | Authorized, declined, captured, capture refused or timed out |
| `PAYMENT_AUTHORIZATION_COMPENSATED` | The processor couldn't be reached |
| `REFUND_CREATED`, `REFUND_PROCESSED`, `REFUND_FAILED` | A refund is requested, completed or declined |
| `SETTLEMENT_PROCESSED`, `SETTLEMENT_FAILED` | A payout completes or fails |

### Delivery and verification

```json
{ "id": "5f0c…", "event": "PAYMENT_STATUS_CHANGED", "created": 1790000000, "payload": { "paymentId": "…", "paymentStatus": "CAPTURED" } }
```

| Header | Meaning |
|---|---|
| `X-PayFlo-Timestamp` | Seconds since the epoch, taken when this attempt is sent |
| `X-PayFlo-Signature` | Hex HMAC-SHA256 of `<timestamp>.<raw body>` with the endpoint's secret |
| `X-PayFlo-Event-Id` | The event's stable id, the same for every retry and replay |

Check the timestamp is within your tolerance, compare the signature in constant time over the bytes received, and drop an event id you've already handled:

```python
import hmac, hashlib, time

def verify(secret: str, raw_body: bytes, signature: str, timestamp: str, tolerance: int = 300) -> bool:
    if abs(time.time() - int(timestamp)) > tolerance:
        return False
    expected = hmac.new(secret.encode(), timestamp.encode() + b"." + raw_body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, signature)
```

Any `2xx` is delivered. Otherwise retries follow after 1 min, 5 min, 30 min, 2 h, 8 h and 24 h, each with a fresh timestamp and signature and the same body; after the seventh failure the delivery is `DEAD`. `common-lib`'s `WebhookSignatures` is the reference implementation.

### Deliveries

operations-service · `/v1/webhook-deliveries`

| Method | Path | Notes |
|---|---|---|
| `GET` | `/v1/webhook-deliveries` | `?status=PENDING \| DELIVERED \| FAILED \| DEAD` |
| `GET` | `/v1/webhook-deliveries/{deliveryId}` | Attempts, next retry, last response code and body, payload |
| `POST` | `/v1/webhook-deliveries/{deliveryId}/replay` | Sends a `DEAD`, `FAILED` or `DELIVERED` delivery again with fresh attempts |

`POST /webhook/success` is operations-service's test receiver: it answers `204` to anything.

## Audit log

| Method | Path | Who |
|---|---|---|
| `GET` | `/v1/merchants/audit-log` | A dashboard login as `OWNER` or `ADMIN`; own entries only. `?action=` |
| `GET` | `/v1/admin/audit-log` | The operator; across merchants. `?merchantId=&action=` |

Each entry has the actor (a user's email, an API key's public id, `platform-admin` or `system`), the action, the target, the facts of the change and the client address, written in the same transaction as the change. Details never hold a secret: an account number is masked, a webhook is recorded by host.

## Admin API

The platform operator's API. It takes `X-Admin-Key` and nothing else; a merchant JWT or API key gets `401`. With `ADMIN_API_KEY` blank the API is off (`403 ADMIN_API_DISABLED`). The development key is `dev-admin-api-key-change-me`.

| Method | Path | Notes |
|---|---|---|
| `GET` | `/v1/admin/merchants` | `?status=`; no payout account, PAN or GSTIN |
| `GET` | `/v1/admin/merchants/{merchantId}` | |
| `POST` | `/v1/admin/merchants/{merchantId}/suspend` | `{ reason }`. Applies to the next request: `403 MERCHANT_SUSPENDED` for keys and sessions |
| `POST` | `/v1/admin/merchants/{merchantId}/reactivate` | Restores the status the merchant had |
| `POST` | `/v1/admin/settlements/run` | `{ merchantId? }`. The nightly run, now, under the same lock (`409 SETTLEMENT_RUN_IN_PROGRESS` while one runs). Audited before it starts |

```bash
curl -s -H 'X-Admin-Key: dev-admin-api-key-change-me' 'http://localhost:8080/v1/admin/merchants?status=ACTIVE'
```

## Idempotency

Any `POST`, `PUT` or `PATCH` may send `X-Idempotency-Key`.

| Case | Answer |
|---|---|
| First request | Claimed in Redis (`SET NX`), scoped by merchant, method and path |
| Repeat within 24 h | The stored status and body replayed; the operation doesn't run again |
| Repeat while the first runs | `409 IDEMPOTENCY_CONFLICT` |
| Same key, different method, path, query or body | `422 IDEMPOTENCY_KEY_REUSED` |
| The first request failed | The claim is released; retry freely |
| A response with a once-only secret (API key or webhook creation and rotation) | Never stored; a repeat is `409 IDEMPOTENT_RESPONSE_NOT_REPLAYABLE` |
| A body with card data or a password | Matched by key alone, never hashed |

Orders, payments and refunds also store the key under a unique `(merchant_id, idempotency_key)` index, so a retry is safe after 24 hours, after Redis drops the key, and when two requests race.

## Rate and velocity limits

| Limit | Default | Setting | Answer |
|---|---|---|---|
| Per API key | 200 / min | `API_KEY_RATE_LIMIT_PER_MINUTE` | `429 RATE_LIMIT_EXCEEDED` + `Retry-After` |
| Per merchant, JWT traffic | 600 / min | `JWT_RATE_LIMIT_PER_MINUTE` | `429` |
| Signup and login per address | 120 / min | `PUBLIC_AUTH_RATE_LIMIT_PER_MINUTE` | `429` |
| Failed authentications per address | 30 / min | `MAX_FAILED_AUTH_PER_MINUTE` | `429`, before any credential is checked |
| Cards tokenized per merchant | 30 / min and 600 / h | `VAULT_TOKENIZE_PER_MINUTE`, `…_PER_HOUR` | `429 CARD_TOKENIZATION_LIMIT_EXCEEDED` |
| Card testing | 20 failed card payments in 10 min that are at least half of the merchant's | `payment.velocity.card.*` | `429 CARD_TESTING_SUSPECTED`, cards only |
| Card payments per order | 5 | `payment.velocity.card.*` | `400 ORDER_CARD_ATTEMPTS_EXCEEDED` |
| Request body | 1 MB | `MAX_REQUEST_BODY_BYTES` | `413 REQUEST_TOO_LARGE`, before authentication |

API-key responses carry `X-RateLimit-Limit` and `X-RateLimit-Remaining`. Limiters are fixed windows in Redis and fail open. Only a decline by the card network counts towards card testing, never PayFlo's own vault failures; UPI, net banking and wallet carry on for a refused merchant. Behind a proxy, set `CLIENT_IP_HEADER`.

## Errors

```json
{ "errorCode": "ORDER_NOT_PAYABLE", "errorDescription": "Order is not in a payable state", "timestamp": "2026-02-09T14:27:16", "fieldErrors": null }
```

Branch on the status and `errorCode`. The gateway's own `401`, `403`, `413` and `429` carry `errorCode` and `errorDescription`.

| Status | `errorCode` |
|---|---|
| 400 | `VALIDATION_FAILED`, `MALFORMED_REQUEST_BODY`, `MISSING_PARAMETER`, `INVALID_PARAMETER`, and business rules such as `ORDER_NOT_PAYABLE`, `REFUND_EXCEEDS_PAYMENT`, `INVALID_DATE_RANGE`, `KYC_INCOMPLETE` |
| 401 | `UNAUTHORIZED` (gateway), `INVALID_CREDENTIALS`, `INVALID_REFRESH_TOKEN` |
| 403 | `MERCHANT_SUSPENDED`, `ROLE_FORBIDDEN`, `DASHBOARD_LOGIN_REQUIRED`, `INCORRECT_PASSWORD`, `ADMIN_REQUIRED`, `ADMIN_API_DISABLED` |
| 404 | `<RESOURCE>_NOT_FOUND`, `ROUTE_NOT_FOUND` |
| 405, 415 | `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE` |
| 409 | `INVALID_STATE_TRANSITION`, `IDEMPOTENCY_CONFLICT`, `IDEMPOTENT_RESPONSE_NOT_REPLAYABLE`, `DATA_INTEGRITY_VIOLATION`, `DUPLICATE_USER_EMAIL`, `ORDER_RECEIPT_DUPLICATE`, `SETTLEMENT_RUN_IN_PROGRESS` |
| 413 | `REQUEST_TOO_LARGE` |
| 422 | `IDEMPOTENCY_KEY_REUSED` |
| 429 | `RATE_LIMIT_EXCEEDED`, `CARD_TOKENIZATION_LIMIT_EXCEEDED`, `CARD_TESTING_SUSPECTED` |
| 503 | `DEPENDENCY_UNAVAILABLE` (`Retry-After: 10`), `AUDIT_LOG_UNAVAILABLE` |
| 500 | `INTERNAL_ERROR` |

No response or stored error field carries raw exception text.

## Test values

The bank side is simulated and deterministic per payment id. A value with no forced outcome follows the approval rates in the second table.

| Method | Value | Result |
|---|---|---|
| Card | `4111 1111 1111 1111` | Authorized and captured |
| Card | `4000 0000 0000 0002` | `FAILED`, `CARD_DECLINED` |
| Card | `4000 0000 0000 0069` | `FAILED`, `CARD_EXPIRED` |
| Card | `4000 0000 0000 0341` | Authorized, capture refused (`CAPTURE_DECLINED`) |
| UPI | `buyer@okaxis` or any `name@bank` | Authorized and captured |
| UPI | `fail@okaxis` | `FAILED`, `UPI_REJECTED` |
| UPI | `capturefail@okaxis` | Authorized, capture refused |
| Net banking | `BANK_CODE_FAIL` / `BANK_CODE_CAPTURE_FAIL` | `BANK_REJECTED` / capture refused |
| Wallet | `wallet_fail` / `wallet_capture_fail` | `WALLET_REJECTED` / capture refused |
| KYC | PAN `AAAAA0000A` | `KYC_REJECTED` |

Card values are tokenized first and paid by token. A refused capture is refused once by default (`payment.simulator.capture.failures-before-success: 1`), so the retry captures.

| Bank callback | Delay | Approved |
|---|---|---|
| Card | 2–6 s | 90% |
| UPI, wallet | 1–3 s | 95% |
| Net banking | 4–10 s | 80% |
| Refunds | 3 s | 95% |
| Payouts | 2–12 s | 95% |

`payment.simulator.chaos-mode` and `settlement.simulator.chaos-mode` override the rates: `NORMAL`, `SLOW` (delays doubled), `SUCCESS`, `FAILURE` or `TIMEOUT` (no answer; the timeout sweepers take over).

## Internal API

`/internal/**` is service-to-service only: never routed by the gateway, and every request needs `X-Internal-Token`. The endpoints and their callers are listed in [architecture](../architecture.md#how-services-talk). Wire types are records in `common-lib`'s `dto` package; `PaymentProcessorResponse` is a sealed type with a `type` discriminator (`PENDING`, `SUCCESS`, `FAILURE`).
