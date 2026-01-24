# APIs

[← Back to docs index](README.md)

> **Phase 2 (microservices).** The endpoints below document the monolith's contract. In the
> microservices split every public route is served through the API gateway and dispatched to the
> owning service — see [Phase 2 API surface](#phase-2-api-surface) at the end of this page for
> what's carried over, what changed, and the internal service-to-service endpoints.

**Rate limiting.** Every endpoint on the API-key chain (`/v1/orders/**`, `/v1/payments/**`,
`/v1/vault/**`) is limited per API key. A successful call carries `X-RateLimit-Limit` and
`X-RateLimit-Remaining` response headers; an over-limit call returns `429` with the standard error
body (`errorCode: RATE_LIMIT_EXCEEDED`) plus `Retry-After` (seconds) and `X-RateLimit-Reset` (epoch
seconds). Endpoints on the JWT chain are not rate limited.

**Idempotency.** Any `POST`/`PUT`/`PATCH` may send an `X-Idempotency-Key` header. A retry with the same
key (scoped to the authenticated merchant when there is one) within 24 hours returns the original
response — same status and body — instead of running the operation again; a retry while the original is
still in flight is meant to return `409` (see [Known gaps](gaps.md)
item 21 — that mapping isn't wired up yet). Only successful responses are remembered, so a failed request can be
retried under the same key.

## `POST /v1/auth/signup`

Registers a new merchant and its first (`OWNER`) user in one call.

**Request body** (`MerchantSignupRequest`):

| Field | Validation | Meaning |
|---|---|---|
| `name` | required, max 50 chars | Contact/display name. |
| `email` | required, valid email format | Merchant login email; also used for the `AppUser`. |
| `password` | required, min 8 chars | Hashed (`PasswordEncoder`/`BCryptPasswordEncoder`) before being stored on `AppUser.passwordHash`. |
| `businessName` | optional, max 50 chars | |
| `businessType` | optional | One of `BusinessType`. |

**Response** — `201 Created` with `MerchantResponse`: `id`, `name`, `email`, `businessName`,
`businessType`, `merchantStatus` (always `PENDING_KYC` on signup).

**Behavior:** rejects with `409 Conflict` (`DuplicateResourceException`, code
`DUPLICATE_MERCHANT_EMAIL`, via `GlobalExceptionHandler`) if `email` already exists on a `Merchant`.
Creates the `Merchant` (status forced to `PENDING_KYC`, ignoring any status sent by the caller —
there isn't one, since `MerchantSignupRequest` has no status field) then the `AppUser`
(`role = OWNER`), both in one `@Transactional` method.

## `POST /v1/auth/login`

Authenticates a merchant user and issues a JWT access token plus a refresh token.

**Request body** (`LoginRequest`): `email` (required, valid email format), `password` (required).

**Response** — `200 OK` with `LoginResponse`: `accessToken` — a JWT carrying `merchant_id` and
`role` claims, HMAC-signed via `JwtUtil` (100-minute expiry) — and `refreshToken` — a raw,
high-entropy random string (see `### POST /v1/auth/refresh` below), shown only in this response.

**Behavior:** `AuthServiceImpl.login` authenticates via `AuthenticationManager.authenticate(new
UsernamePasswordAuthenticationToken(email, password))` — backed by a real `DaoAuthenticationProvider`
+ `merchant/security/MerchantUserDetailsService` + `BCryptPasswordEncoder` (`WebSecurityConfig`) —
then separately looks up the `AppUser` by email to read its `merchant_id`/`role` for the token, and
calls `RefreshTokenService.issue(appUser)` for the refresh token. An incorrect password or an
unknown email both fail the same way: `MerchantUserDetailsService` throws
`UsernameNotFoundException` for a missing user (rather than a `ResourceNotFoundException` that
would leak a `404`), which `DaoAuthenticationProvider` folds into the same
`BadCredentialsException` as a wrong password either way — `GlobalExceptionHandler` maps that to a
uniform `401` (`INVALID_CREDENTIALS`, "Invalid email or password"). The returned access token *is*
validated on later requests — `merchant/security/JwtAuthenticationFilter`, on `WebSecurityConfig`'s
`jwtChain` — for every route under that chain except signup/login/refresh/logout/webhook.

## `POST /v1/auth/refresh`

Exchanges a valid, unused refresh token for a brand-new access+refresh pair. The presented refresh
token is consumed in the process — single-use, not repeatable.

**Request body** (`RefreshTokenRequest`): `refreshToken` (required).

**Response** — `200 OK` with `LoginResponse` (same shape as login): a new `accessToken` and a new
`refreshToken`. Both differ from whatever was presented.

**Behavior:** `RefreshTokenService.rotate(rawToken)` hashes the presented token
(`HashUtil.sha256Hex`) and looks it up by that hash. Rejects with `401`
(`INVALID_REFRESH_TOKEN`, generic message — deliberately not distinguishing *why*, to avoid leaking
whether a token merely expired vs. was already used vs. never existed) if: not found, already
`revoked` (logged server-side as a reuse warning — a stronger signal than an expiry, since it means
someone presented a token that was already exchanged), or past `expiresAt`. On success, marks the
old row `revoked` and issues a new pair via `AuthServiceImpl.refresh`, wrapped in one
`@Transactional` — `rotate()` and `issue()` are each independently transactional on
`RefreshTokenServiceImpl`, so without an outer transaction a failure between the two could revoke
the old token without ever handing back a replacement. **No route requires a token here** —
`/v1/auth/refresh` is in `jwtChain`'s `permitAll()` list, same as signup/login, since the entire
point is to work *after* the access token has expired. A stale/garbled `Authorization: Bearer`
header attached anyway is still validated by `JwtAuthenticationFilter` regardless of the route
being public, and now correctly returns `401` (`INVALID_ACCESS_TOKEN`) rather than an empty `200`
— see [Known gaps](gaps.md) item 16 for why that fix was needed.

## `POST /v1/auth/logout`

Revokes a refresh token, ending that session. Doesn't touch the caller's current access token
(still valid until it naturally expires) — only prevents it from ever being renewed via this
refresh token again.

**Request body** (`RefreshTokenRequest`): `refreshToken` (required).

**Response** — `204 No Content`.

**Behavior:** `RefreshTokenService.revoke(rawToken)` — if a matching, non-revoked row exists, marks
it `revoked`. If no row matches the hash, silently no-ops and still returns `204` — logout
deliberately never reveals whether a token existed, matching `/refresh`'s generic-error philosophy
from the other direction.

> **`merchantId` is no longer a path parameter for any endpoint below** (moved 2025-11-24) — the
> route is now `/v1/merchants/api-keys`, and every method takes its merchant from
> `MerchantContext` (populated by `JwtAuthenticationFilter` from the caller's JWT). All four
> require a valid JWT.

## `POST /v1/merchants/api-keys`

Generates a new API key for the authenticated merchant, returning the secret in plaintext exactly
once.

**Request body** (`CreateApiKeyRequest`): `environment` — one of `Environment` (`TEST`, `LIVE`).

**Response** — `201 Created` with `ApiKeyCreateResponse`: `id`, `keyId` (format
`pfx_<environment>_<24-byte random>`, e.g. `pfx_test_ab12...`), `keySecret` (the raw, show-once
secret — never persisted or returned again after this response), `environment`.

**Behavior:** `keyId` and the raw secret are generated via `RandomizerUtil.randomBase64`
(`SecureRandom`-backed, URL-safe Base64, no padding); the raw secret is hashed
(`PasswordEncoder`/`BCryptPasswordEncoder`) before being written to `ApiKey.keySecretHash` — the
response is built directly from the raw string generated a moment earlier, not read back off the
(now-hashed) entity. Since `merchantId` now comes from the caller's own JWT rather than an
arbitrary path value, a key can only ever be generated for the authenticated merchant — the old
"any caller can generate a key for any merchantId" gap no longer applies.

## `GET /v1/merchants/api-keys`

Lists all API keys belonging to the authenticated merchant. Never returns the secret.

**Response** — `200 OK` with a list of `ApiKeyResponse`: `id`, `keyId`, `environment`, `enabled`,
`lastUsedAt`, `createdAt`.

## `DELETE /v1/merchants/api-keys/{keyId}`

Revokes an API key. A soft revoke — sets `ApiKey.enabled = false`, doesn't delete the row (there's
no dedicated `revoked_at` timestamp on `API_KEY`, unlike `CARD_TOKEN.revoked_at`).

**Path parameter:** `keyId`.

**Response** — `204 No Content`.

**Behavior:** rejects with `404 Not Found` (`ResourceNotFoundException`) if `keyId` doesn't exist
*or* belongs to a different merchant than the caller's.

## `POST /v1/merchants/api-keys/{keyId}/rotate`

Rotates an API key: generates a new secret, keeps the old one valid for a 24-hour grace period.

**Path parameter:** `keyId`.

**Response** — `200 OK` with `ApiKeyCreateResponse`: `id`, `keyId` (unchanged), `keySecret` (the
new raw secret, shown once, hashed before storage the same way `create` does), `environment`.

**Behavior:** rejects with `404 Not Found` (`ResourceNotFoundException`) if `keyId` doesn't exist
or belongs to a different merchant, and with `409 Conflict` (`ConflictException`, code
`API_KEY_DISABLED`) if the key is currently disabled (revoked). On success: moves the current
`keySecretHash` into `previousKeySecretHash`, sets a new `keySecretHash`, stamps `rotatedAt`, and
sets `gracePeriodExpiresAt` to 24 hours from now (`ApiKey.isInGracePeriod()` uses this to accept
the previous secret during the window). `keyId` itself doesn't change.

## `POST /v1/orders`

Creates an order — the first payment-domain endpoint. `merchantId` comes from `MerchantContext`
(populated by `ApiKeyAuthenticationFilter` from the matched API key's owning merchant), not a
request field — the order always belongs to whichever merchant authenticated. Requires a valid API
key (`Authorization: Basic base64(keyId:secret)`), not a JWT.

**Request body** (`CreateOrderRequest`): `amount` (required, `Money`), `receipt` (optional, max 100
chars, merchant's own order identifier), `notes` (optional, freeform JSON object), `expiresAt`
(optional, defaults to `payment.order.default-order-expiry-minutes` — 30 — minutes from now),
`customer` (optional, `name`/`email`/`phone` — when present and `email` is non-blank, resolved
through `CustomerService.findOrCreate`, looked up or created by merchant+email).

**Response** — `201 Created` with `OrderResponse`: `id`, `merchantId`, `customerId` (the resolved
customer, `null` if none was supplied), `receipt`, `amount`, `status` (always `CREATED` on
creation), `attempts` (always `0`), `notes`, `expiresAt`, `createdAt`.

**Behavior:** rejects with `409 Conflict` (`DuplicateResourceException`, code
`ORDER_RECEIPT_DUPLICATE`) if `receipt` is non-null and already used by this merchant. On success,
publishes an `ORDER_CREATED` event to the outbox (see [Practices](practices.md)) after the order is
saved.

## `GET /v1/orders/{orderId}`

Fetches a single order by ID, scoped to the caller's merchant (`MerchantContext`, resolved from the
API key) — an order belonging to a different merchant is treated as not found rather than a `403`.
Requires a valid API key, not a JWT.

**Path parameters:** `orderId`.

**Response** — `200 OK` with `OrderResponse`: same shape as the create response.

**Behavior:** rejects with `404 Not Found` (`ResourceNotFoundException`) if `orderId` doesn't
exist or doesn't belong to the caller's merchant.

## `POST /v1/orders/{orderId}/cancel`

Cancels an order, scoped to the caller's merchant like the other order endpoints. Requires a valid
API key, not a JWT.

**Path parameters:** `orderId`.

**Response** — `200 OK` with `OrderResponse`: the order with `status` now `CANCELLED`.

**Behavior:** rejects with `404 Not Found` (`ResourceNotFoundException`) if `orderId` doesn't
exist or doesn't belong to the caller's merchant, and with `409 Conflict`
(`BusinessRuleViolationException`, code `ORDER_CANNOT_CANCEL`) if the order is already `CANCELLED`
or `PAID`. On success, publishes an `ORDER_CANCELLED` event to the outbox.

## `GET /v1/orders/{orderId}/payments`

Lists every payment attempt made against an order, scoped to the caller's merchant like the other
order endpoints. Requires a valid API key, not a JWT.

**Path parameters:** `orderId`.

**Response** — `200 OK` with a `List<PaymentResponse>`: `id`, `orderId`, `merchantId`, `amount`,
`status`, `method`, `methodDetails`, `errorCode`, `errorDescription`, `capturedAt`, `createdAt`
per payment (empty list if the order has no payment attempts yet).

**Behavior:** rejects with `404 Not Found` (`ResourceNotFoundException`) if `orderId` doesn't
exist or doesn't belong to the caller's merchant.

## `POST /v1/payments`

Initiates a payment attempt against an order. `merchantId` comes from `MerchantContext`, same as
`OrderController`. Requires a valid API key, not a JWT.

**Request body** (`PaymentInitRequest`): `orderId` (required), `method` (required, `PaymentMethod`
— `CARD`/`NETBANKING`/`UPI`/`WALLET`), `methodDetails` (optional, freeform JSON — for `CARD`, a
`token` from `POST /v1/vault/tokenize`; for `NETBANKING`, a `bank` code; for `UPI`, a `vpa`; for
`WALLET`, a `walletId`).

**Response** — `201 Created` with `PaymentResponse`: `id`, `orderId`, `merchantId`, `amount`
(copied from the order), `status`, `method`, `methodDetails`, `errorCode`, `errorDescription`,
`capturedAt`, `createdAt`. All three implemented methods now return a correctly-formed response —
see the note on `PaymentAdapter` below for the per-method mock-acquirer rules.

**Behavior:** locks the order row (`SELECT ... FOR UPDATE` via
`OrderRepository.findByIdAndMerchantIdForUpdate`) to serialize concurrent payment attempts against
the same order; rejects with `404 Not Found` (`ResourceNotFoundException`) if `orderId` doesn't
exist or doesn't belong to the caller's merchant, and with `409 Conflict`
(`BusinessRuleViolationException`, code `ORDER_NOT_PAYABLE`) unless the order is `CREATED` or
`ATTEMPTED`. On success: sets the order
to `ATTEMPTED` and increments its `attempts`, creates a `Payment` row (`status = CREATED`, a fresh
random `idempotencyKey` — not yet enforced, see [Known gaps](gaps.md)),
fires `AUTHORIZE_ATTEMPT` through `PaymentTransitionService` (`CREATED` → `AUTHORIZING`), and
routes the request through `PaymentGatewayRouter` to the method's `PaymentAdapter`. The returned
`PaymentResult` is then applied to the `Payment`: `Pending` sets `processorReference` (status stays
`AUTHORIZING` — nothing advances it further yet, so `POST .../capture` always rejects for now, see
[Known gaps](gaps.md)); `Failure` fires `AUTHORIZE_FAIL` (`status =
FAILED`, plus `errorCode`/`errorDescription`, also recorded as the transition log's `reason`);
`Success` is treated as an invalid synchronous state
and discarded (`return null`) — currently unreachable dead code, since no processor produces
`Success` today (see below). On success, publishes a `PAYMENT_CREATED` event to the outbox. Each processor's mock logic recognizes several distinct test
scenarios — modeled on how real gateway sandboxes (Stripe/Razorpay-style test cards, NPCI-style
test VPAs) document multiple named test values per outcome rather than one binary pass/fail — all
mapping to `status: FAILED` with a specific `errorCode`; anything not matching a known test value
comes back `status: AUTHORIZING` with `processorReference` set (the mock "payment is being
processed" state):

| Method | Trigger | `errorCode` |
|---|---|---|
| `CARD` | PAN `4000000000000002` | `CARD_DECLINED` |
| `CARD` | PAN `4000000000000069` | `CARD_EXPIRED` |
| `CARD` | PAN `4000000000009995` | `INSUFFICIENT_FUNDS` |
| `CARD` | PAN `4000000000000119` | `PROCESSING_ERROR` |
| `CARD` | PAN `4100000000000019` | `FRAUD_SUSPECTED` |
| `NETBANKING` | `methodDetails.bank` missing/blank | `INVALID_BANK` |
| `NETBANKING` | `methodDetails.bank == "BANK_CODE_FAIL"` | `BANK_REJECTED` |
| `NETBANKING` | `methodDetails.bank == "BANK_CODE_INSUFFICIENT_FUNDS"` | `INSUFFICIENT_FUNDS` |
| `NETBANKING` | `methodDetails.bank == "BANK_CODE_TIMEOUT"` | `BANK_TIMEOUT` |
| `UPI` | `methodDetails.vpa` missing or not `handle@bank` shaped | `INVALID_VPA` |
| `UPI` | `methodDetails.vpa == "fail@okaxis"` | `UPI_REJECTED` |
| `UPI` | `methodDetails.vpa == "nofunds@okaxis"` | `INSUFFICIENT_FUNDS` |
| `WALLET` | `methodDetails.walletId` missing/blank | `INVALID_WALLET` |
| `WALLET` | `methodDetails.walletId == "fail_wallet"` | `WALLET_REJECTED` |
| `WALLET` | `methodDetails.walletId == "low_balance_wallet"` | `INSUFFICIENT_FUNDS` |

`CARD` additionally routes through `CardPaymentAdapter`/`VaultService.charge` (decrypts the
vaulted card behind `methodDetails.token` first — a missing/unknown `token`, or missing
`methodDetails` entirely, is caught and reported as `status: FAILED` with code `CARD_FAILED`
rather than crashing) instead of calling the processor directly. All four processors return
`Pending` rather than `Success` on their happy path specifically to avoid the discarded-response
bug above. Note the input-validation entries (`INVALID_BANK`/`INVALID_VPA`/`INVALID_WALLET`) are a
behavior change from earlier: previously, an omitted `bank`/`vpa`/`walletId` fell through to the
success path (`Pending`) rather than failing — real systems can't process a payment without
knowing which bank/VPA/wallet to charge, so this now fails explicitly instead.

All four `PaymentMethod` values now have both a `PaymentAdapter` and a `PaymentProcessor`
registered — `UnsupportedPaymentMethodException`/`400 Bad Request` (see [Known
gaps](gaps.md)) is currently unreachable through either router given
today's enum values, but stays in place as the defined behavior for any future `PaymentMethod`
added without adapters to match.

## `POST /v1/payments/{paymentId}/capture`

Captures a previously-authorized payment — the second step of the auth-then-capture flow (see
`PaymentAdapter.capture(UUID)` below). `merchantId` comes from `MerchantContext`, same as the
other payment/order endpoints. Requires a valid API key, not a JWT.

**Path parameters:** `paymentId`.

**Response** — `200 OK` with `PaymentResponse`.

**Behavior:** locks the payment row (`SELECT ... FOR UPDATE` via
`PaymentRepository.findByIdAndMerchantIdForUpdate`); rejects with `404 Not Found`
(`ResourceNotFoundException`) if `paymentId` doesn't exist or doesn't belong to the hardcoded
merchant. Fires `CAPTURE_REQUEST` through `PaymentTransitionService` (`AUTHORIZED` → `CAPTURING`)
— rejects with `409 Conflict` (`InvalidStateTransitionException`, code
`INVALID_STATE_TRANSITION`) if the payment isn't currently `AUTHORIZED`. Then calls
`PaymentGatewayRouter.capture(method, paymentId)` and applies the result via the same service:
`Success` fires `CAPTURE_SUCCESS` (`status = CAPTURED`, sets `capturedAt`); `Failure` fires
`CAPTURE_FAIL` (reverts to `status = AUTHORIZED`, retryable, with `errorCode`/`errorDescription`,
also recorded as the transition log's `reason`); `Pending` fires `CAPTURE_PENDING` (a
self-transition — stays `CAPTURING`, since a retry while the original attempt is still genuinely in
flight risks a double capture); a `null` result (adapter not implemented) sets `status =
AUTHORIZED` directly, without going through the transition service, since that's not a real domain
event. On success, publishes a `PAYMENT_STATUS_CHANGED` event to the
outbox. In practice: no payment currently ever reaches
`AUTHORIZED` through any live path — nothing fires `AUTHORIZE_SUCCESS` anywhere yet, since every
processor's happy path returns `Pending` (which just sets `processorReference` and leaves the
payment in `AUTHORIZING`) rather than a terminal outcome — so every capture call today is rejected
with `409 INVALID_STATE_TRANSITION` before any adapter is even invoked — see
[Known gaps](gaps.md).

## `POST /v1/vault/tokenize`

Tokenizes a card: encrypts and stores it, returning an opaque token that stands in for the card in
later requests (e.g. `PaymentInitRequest.methodDetails` for a `CARD` payment) instead of ever
handling the raw PAN again. `merchantId` comes from `MerchantContext`, same as the other
endpoints. Requires a valid API key, not a JWT.

**Request body** (`TokenizeRequest`): `pan` (required, 13–19 digits, must pass a Luhn checksum),
`cvv` (required, 3–4 digits — validated but **never stored**, per PCI DSS), `expiryMonth`
(required, 1–12), `expiryYear` (required, must not be in the past), `customerId` (optional),
`cardHolderName` (required, min 3 characters).

**Response** — `201 Created` with `TokenizeResponse`: `token`, `lastFour`, `brand` (detected from
the PAN's leading digits), `expiryMonth`, `expiryYear`. The raw PAN is never echoed back.

**Behavior:** detects `CardBrand` from the PAN prefix; generates a random 256-bit AES data
encryption key (DEK) per card, encrypts the PAN with it (`AesBytesEncryptor`, GCM mode), then
encrypts (wraps) that DEK itself with a separate master key-encryption-key (KEK) sourced from
`vault.master-key` (env var `VAULT_MASTER_KEY`, with a dev-only default — see
[Known gaps](gaps.md)). Saves a `VaultCard` row (encrypted PAN,
wrapped DEK, brand, last 4 digits, first 6 digits, expiry, cardholder name) and a `CardToken` row
(the generated token, a reference to the `VaultCard`, `customerId`, `merchantId`) linking a
merchant-facing token to it.

## `POST /v1/merchants/webhooks`

Registers a new webhook config for the caller's merchant. Requires a valid JWT (`jwtChain`), not an
API key.

**Request body** (`UpdateWebhookConfigRequest`): `targetUrl` (required, max 500 chars, must be
`http(s)://...`), `eventTypes` (optional, max 1000 chars — comma-separated event type names, e.g.
`"PAYMENT_STATUS_CHANGED,ORDER_CREATED"`; null/blank/`"ALL"` subscribes to every event type).

**Response** — `200 OK` with `WebhookConfigResponse`: `id`, `targetUrl`, `webhookSecret` (a
server-generated random secret, shown **only on this response** — encrypted at rest with the
shared `BytesEncryptor`, see [Practices](practices.md)), `enabled` (always `true` on creation),
`eventTypes`.

## `GET /v1/merchants/webhooks`

Lists every webhook config for the caller's merchant. Requires a valid JWT.

**Response** — `200 OK` with a `List<WebhookConfigResponse>` — `webhookSecret` omitted
(`@JsonInclude(NON_NULL)`) on every entry.

## `GET /v1/merchants/webhooks/{id}`

Fetches a single webhook config by ID, scoped to the caller's merchant. Requires a valid JWT.

**Path parameters:** `id`.

**Response** — `200 OK` with `WebhookConfigResponse` (`webhookSecret` omitted).

**Behavior:** rejects with `404 Not Found` (`ResourceNotFoundException`) if `id` doesn't exist or
doesn't belong to the caller's merchant.

## `PUT /v1/merchants/webhooks/{id}`

Updates a webhook config's `targetUrl`/`eventTypes`. Requires a valid JWT.

**Path parameters:** `id`.

**Request body:** same `UpdateWebhookConfigRequest` as create.

**Response** — `200 OK` with `WebhookConfigResponse` (`webhookSecret` omitted — the secret itself
can't be changed via update, only rotated by deleting and recreating the config).

**Behavior:** rejects with `404 Not Found` (`ResourceNotFoundException`) if `id` doesn't exist or
doesn't belong to the caller's merchant. `enabled` is not settable through this endpoint (no way
to disable a config without deleting it yet).

## `DELETE /v1/merchants/webhooks/{id}`

Deletes a webhook config. Requires a valid JWT.

**Path parameters:** `id`.

**Response** — `204 No Content`.

**Behavior:** rejects with `404 Not Found` (`ResourceNotFoundException`) if `id` doesn't exist or
doesn't belong to the caller's merchant.

## Phase 2 API surface

In the microservices split, clients call the API gateway only; it authenticates the request and
forwards it to the owning service (resolved through Eureka). Request/response bodies are unchanged
from the sections above unless noted.

| Route | Owning service | Auth at gateway | Notes |
|---|---|---|---|
| `POST /v1/auth/signup`, `POST /v1/auth/login` | merchant-service | Public | `/v1/auth/refresh` and `/v1/auth/logout` not carried over yet |
| `/v1/merchants/api-keys/**` | merchant-service | JWT | Key ids now prefixed `fp_<env>_` |
| `/v1/merchants/webhooks/**` | merchant-service | JWT | Unchanged |
| `POST /v1/orders` | payment-service | API key or JWT | Customer resolved via merchant-service over Feign |
| `POST /v1/payments`, `POST /v1/payments/{paymentId}/capture` | payment-service | API key or JWT | Initiation runs as a saga; `CARD` charges go through vault-service |
| `POST /v1/vault/tokenize` | vault-service | API key or JWT | Unchanged |

`GET /v1/orders/{orderId}`, `POST /v1/orders/{orderId}/cancel`, and `GET /v1/orders/{orderId}/payments`
exist in the monolith only; they haven't been ported to payment-service yet.

**Internal endpoints** (`/internal/**`) are never routed by the gateway — they're reachable only
service-to-service via Eureka and exchange the shared DTOs from `common-lib`:

| Endpoint | Service | Caller |
|---|---|---|
| `GET /internal/api-keys/{keyId}` | merchant-service | api-gateway-service (API key cache miss) |
| `POST /internal/customers/find-or-create` | merchant-service | payment-service (order creation) |
| `GET /internal/merchants/{merchantId}/webhook-targets?eventType=` | merchant-service | operations-service (webhook fan-out) |
| `GET /internal/merchants/active-ids` | merchant-service | operations-service (settlement) |
| `GET /internal/merchants/{merchantId}/settlement-bank-details` | merchant-service | operations-service (settlement) |
| `POST /internal/vault/charge` | vault-service | payment-service (`CardPaymentAdapter`) |
| `GET /internal/payments/unsettled-captured?merchantId=` | payment-service | operations-service (settlement) |
| `POST /internal/payments/mark-settled` | payment-service | operations-service (settlement) |
