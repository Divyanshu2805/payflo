# APIs

[← Back to docs index](README.md)

## `POST /v1/auth/signup`

Registers a new merchant and its first (`OWNER`) user in one call.

**Request body** (`MerchantSignupRequest`):

| Field | Validation | Meaning |
|---|---|---|
| `name` | required, max 50 chars | Contact/display name. |
| `email` | required, valid email format | Merchant login email; also used for the `AppUser`. |
| `password` | required, min 8 chars | Stored as-is on `AppUser.passwordHash` — see [Known gaps](gaps.md), no hashing yet. |
| `businessName` | optional, max 50 chars | |
| `businessType` | optional | One of `BusinessType`. |

**Response** — `201 Created` with `MerchantResponse`: `id`, `name`, `email`, `businessName`,
`businessType`, `merchantStatus` (always `PENDING_KYC` on signup).

**Behavior:** rejects with `409 Conflict` (`DuplicateResourceException`, code
`DUPLICATE_MERCHANT_EMAIL`, via `GlobalExceptionHandler`) if `email` already exists on a `Merchant`.
Creates the `Merchant` (status forced to `PENDING_KYC`, ignoring any status sent by the caller —
there isn't one, since `MerchantSignupRequest` has no status field) then the `AppUser`
(`role = OWNER`), both in one `@Transactional` method.

## `POST /v1/merchants/{merchantId}/api-keys`

Generates a new API key for a merchant, returning the secret in plaintext exactly once.

**Path parameter:** `merchantId` — the owning merchant's UUID.

**Request body** (`CreateApiKeyRequest`): `environment` — one of `Environment` (`TEST`, `LIVE`).

**Response** — `201 Created` with `ApiKeyCreateResponse`: `id`, `keyId` (format
`pfx_<environment>_<24-byte random>`, e.g. `pfx_test_ab12...`), `keySecret` (the raw, show-once
secret — see [Known gaps](gaps.md), stored unhashed), `environment`.

**Behavior:** rejects with `404 Not Found` (`ResourceNotFoundException`, via
`GlobalExceptionHandler`) if `merchantId` doesn't exist. `keyId` and the raw secret are generated
via `RandomizerUtil.randomBase64` (`SecureRandom`-backed, URL-safe Base64, no padding); the raw
secret is written directly to `ApiKey.keySecretHash` with no hashing applied. No authorization
check yet — any caller can generate a key for any `merchantId`.

## `GET /v1/merchants/{merchantId}/api-keys`

Lists all API keys belonging to a merchant. Never returns the secret.

**Path parameter:** `merchantId` — the owning merchant's UUID.

**Response** — `200 OK` with a list of `ApiKeyResponse`: `id`, `keyId`, `environment`, `enabled`,
`lastUsedAt`, `createdAt`.

**Behavior:** does **not** validate that `merchantId` exists — an unknown `merchantId` returns an
empty list (`200 OK`) rather than `404`, unlike the create endpoint. No authorization check yet,
same as create.

## `DELETE /v1/merchants/{merchantId}/api-keys/{keyId}`

Revokes an API key. A soft revoke — sets `ApiKey.enabled = false`, doesn't delete the row (there's
no dedicated `revoked_at` timestamp on `API_KEY`, unlike `CARD_TOKEN.revoked_at`).

**Path parameters:** `merchantId`, `keyId`.

**Response** — `204 No Content`.

**Behavior:** rejects with `404 Not Found` (`ResourceNotFoundException`) if `keyId` doesn't exist
*or* belongs to a different merchant than `merchantId` — the merchant-ownership check is enforced
here, unlike list. No authorization check on the caller themselves yet.

## `POST /v1/merchants/{merchantId}/api-keys/{keyId}/rotate`

Rotates an API key: generates a new secret, keeps the old one valid for a 24-hour grace period.

**Path parameters:** `merchantId`, `keyId`.

**Response** — `200 OK` with `ApiKeyCreateResponse`: `id`, `keyId` (unchanged), `keySecret` (the
new raw secret, shown once — same unhashed-storage gap as create, see
[Known gaps](gaps.md)), `environment`.

**Behavior:** rejects with `404 Not Found` (`ResourceNotFoundException`) if `keyId` doesn't exist
or belongs to a different merchant, and with `409 Conflict` (`ConflictException`, code
`API_KEY_DISABLED`) if the key is currently disabled (revoked). On success: moves the current
`keySecretHash` into `previousKeySecretHash`, sets a new `keySecretHash`, stamps `rotatedAt`, and
sets `gracePeriodExpiresAt` to 24 hours from now (`ApiKey.isInGracePeriod()` uses this to accept
the previous secret during the window). `keyId` itself doesn't change.

## `POST /v1/orders`

Creates an order — the first payment-domain endpoint. **`merchantId` is currently hardcoded** to a
fixed test UUID in `OrderController` (a `private final UUID` field, not derived from any caller
identity) since there's no auth yet; every order created through this endpoint belongs to that same
merchant regardless of who calls it. See [Known gaps](gaps.md).

**Request body** (`CreateOrderRequest`): `amount` (required, `Money`), `receipt` (optional, max 100
chars, merchant's own order identifier), `notes` (optional, freeform JSON object), `expiresAt`
(optional, defaults to `payment.order.default-order-expiry-minutes` — 30 — minutes from now).

**Response** — `201 Created` with `OrderResponse`: `id`, `merchantId`, `receipt`, `amount`,
`status` (always `CREATED` on creation), `attempts` (always `0`), `notes`, `expiresAt`,
`createdAt`.

**Behavior:** rejects with `409 Conflict` (`DuplicateResourceException`, code
`ORDER_RECEIPT_DUPLICATE`) if `receipt` is non-null and already used by this merchant.

## `GET /v1/orders/{orderId}`

Fetches a single order by ID, scoped to the same hardcoded test merchant `POST /v1/orders` uses
(see [Known gaps](gaps.md)) — an order belonging to a different
merchant is treated as not found rather than a `403`.

**Path parameters:** `orderId`.

**Response** — `200 OK` with `OrderResponse`: same shape as the create response.

**Behavior:** rejects with `404 Not Found` (`ResourceNotFoundException`) if `orderId` doesn't
exist or doesn't belong to the hardcoded merchant.

## `POST /v1/orders/{orderId}/cancel`

Cancels an order, scoped to the same hardcoded test merchant the other order endpoints use (see
[Known gaps](gaps.md)).

**Path parameters:** `orderId`.

**Response** — `200 OK` with `OrderResponse`: the order with `status` now `CANCELLED`.

**Behavior:** rejects with `404 Not Found` (`ResourceNotFoundException`) if `orderId` doesn't
exist or doesn't belong to the hardcoded merchant, and with `409 Conflict` (`ConflictException`,
code `ORDER_CANNOT_CANCEL`) if the order is already `CANCELLED` or `PAID`.

## `GET /v1/orders/{orderId}/payments`

Lists every payment attempt made against an order, scoped to the same hardcoded test merchant the
other order endpoints use (see [Known gaps](gaps.md)).

**Path parameters:** `orderId`.

**Response** — `200 OK` with a `List<PaymentResponse>`: `id`, `orderId`, `merchantId`, `amount`,
`status`, `method`, `methodDetails`, `errorCode`, `errorDescription`, `capturedAt`, `createdAt`
per payment (empty list if the order has no payment attempts yet).

**Behavior:** rejects with `404 Not Found` (`ResourceNotFoundException`) if `orderId` doesn't
exist or doesn't belong to the hardcoded merchant.

## `POST /v1/payments`

Initiates a payment attempt against an order. **`merchantId` is hardcoded** in `PaymentController`
the same way `OrderController`'s is — a separate fixed test UUID instance field, not derived from
any caller identity (see [Known gaps](gaps.md)).

**Request body** (`PaymentInitRequest`): `orderId` (required), `method` (required, `PaymentMethod`
— `CARD`/`NETBANKING`/`UPI`/`WALLET`, though `WALLET` has no adapter registered yet and rejects
with `400 Bad Request` — see the note on `PaymentAdapter` below), `methodDetails` (optional,
freeform JSON — for `CARD`, a `token` from `POST /v1/vault/tokenize`; for `NETBANKING`, a `bank`
code; for `UPI`, a `vpa`).

**Response** — `201 Created` with `PaymentResponse`: `id`, `orderId`, `merchantId`, `amount`
(copied from the order), `status`, `method`, `methodDetails`, `errorCode`, `errorDescription`,
`capturedAt`, `createdAt`. All three implemented methods now return a correctly-formed response —
see the note on `PaymentAdapter` below for the per-method mock-acquirer rules.

**Behavior:** locks the order row (`SELECT ... FOR UPDATE` via
`OrderRepository.findByIdAndMerchantIdForUpdate`) to serialize concurrent payment attempts against
the same order; rejects with `404 Not Found` (`ResourceNotFoundException`) if `orderId` doesn't
exist or doesn't belong to the hardcoded merchant, and with `409 Conflict` (`ConflictException`,
code `ORDER_NOT_PAYABLE`) unless the order is `CREATED` or `ATTEMPTED`. On success: sets the order
to `ATTEMPTED` and increments its `attempts`, creates a `Payment` row (`status = CREATED`, a fresh
random `idempotencyKey` — not yet enforced, see [Known gaps](gaps.md)),
fires `AUTHORIZE_ATTEMPT` through `PaymentTransitionService` (`CREATED` → `AUTHORIZING`), and
routes the request through `PaymentGatewayRouter` to the method's `PaymentAdapter`. The returned
`PaymentResult` is then applied to the `Payment`: `Pending` sets `processorReference` (status stays
`AUTHORIZING` — nothing advances it further yet, so `POST .../capture` always rejects for now, see
[Known gaps](gaps.md)); `Failure` fires `AUTHORIZE_FAIL` (`status =
FAILED`, plus `errorCode`/`errorDescription`); `Success` is treated as an invalid synchronous state
and discarded (`return null`) — currently unreachable dead code, since no processor produces
`Success` today (see below). In practice, per `method`:
- `CARD` — `CardPaymentAdapter` decrypts the vaulted card behind `methodDetails.token` (via
  `VaultService.charge`) and routes it through the same processor layer. A test-declined/expired
  PAN (`4000000000000002`/`4000000000000069`) comes back `status: FAILED`; anything else comes
  back `status: AUTHORIZING` with `processorReference` set. A missing/unknown `token` (or missing
  `methodDetails` entirely) is caught and reported as `status: FAILED` with code `CARD_FAILED`
  rather than crashing.
- `NETBANKING`/`UPI` — `methodDetails.bank == "BANK_CODE_FAIL"` for netbanking, `methodDetails.vpa
  == "fail@okaxis"` for UPI, comes back `status: FAILED` with a generated `errorCode`. Anything
  else comes back `status: AUTHORIZING` with `processorReference` set, same as card's non-failure
  path — both processors return `Pending` rather than `Success` on their happy path specifically to
  avoid the discarded-response bug above.
- `WALLET` — no `PaymentAdapter` is registered for it in `PaymentAdapterConfig`.
  `PaymentGatewayRouter` throws `UnsupportedPaymentMethodException` (code
  `UNSUPPORTED_PAYMENT_METHOD`), which `GlobalExceptionHandler` maps to `400 Bad Request` — the
  whole request rolls back cleanly (no orphaned `Payment`/`Order` row), rather than the unhandled
  `500` this used to be.

## `POST /v1/payments/{paymentId}/capture`

Captures a previously-authorized payment — the second step of the auth-then-capture flow (see
`PaymentAdapter.capture(UUID)` below). **`merchantId` is hardcoded** the same way the other
payment/order endpoints are.

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
`CAPTURE_FAIL` (reverts to `status = AUTHORIZED`, retryable, with `errorCode`/`errorDescription`);
`Pending` fires `CAPTURE_PENDING` (a self-transition — stays `CAPTURING`, since a retry while the
original attempt is still genuinely in flight risks a double capture); a `null` result (adapter
not implemented) sets `status = AUTHORIZED` directly, without going through the transition
service, since that's not a real domain event. In practice: no payment currently ever reaches
`AUTHORIZED` through any live path — nothing fires `AUTHORIZE_SUCCESS` anywhere yet, since every
processor's happy path returns `Pending` (which just sets `processorReference` and leaves the
payment in `AUTHORIZING`) rather than a terminal outcome — so every capture call today is rejected
with `409 INVALID_STATE_TRANSITION` before any adapter is even invoked — see
[Known gaps](gaps.md).

## `POST /v1/vault/tokenize`

Tokenizes a card: encrypts and stores it, returning an opaque token that stands in for the card in
later requests (e.g. `PaymentInitRequest.methodDetails` for a `CARD` payment) instead of ever
handling the raw PAN again. **`merchantId` is hardcoded** the same way the other endpoints are.

**Request body** (`TokenizeRequest`): `pan` (required, 13–19 digits, must pass a Luhn checksum),
`cvv` (required, 3–4 digits — validated but **never stored**, per PCI DSS), `expiryMonth`
(required, 1–12), `expiryYear` (required, must not be in the past), `customerId` (optional),
`cardHolderName` (required, min 3 characters).

**Response** — `201 Created` with `TokenizeResponse`: `token`, `lastFour`, `brand` (detected from
the PAN's leading digits), `expiryMonth`, `expiryYear`. The raw PAN is never echoed back.

**Behavior:** detects `CardBrand` from the PAN prefix; generates a random 256-bit AES data
encryption key (DEK) per card, encrypts the PAN with it (`AesBytesEncryptor`, GCM mode), then
encrypts (wraps) that DEK itself with a separate master key-encryption-key (KEK) sourced from
`vault.encryption.master-key` (env var `VAULT_MASTER_KEY`, with a dev-only default — see
[Known gaps](gaps.md)). Saves a `VaultCard` row (encrypted PAN,
wrapped DEK, brand, last 4 digits, first 6 digits, expiry, cardholder name) and a `CardToken` row
(the generated token, a reference to the `VaultCard`, `customerId`, `merchantId`) linking a
merchant-facing token to it.
