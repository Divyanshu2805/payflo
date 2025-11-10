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
— `CARD`/`NETBANKING`/`UPI`/`WALLET`), `methodDetails` (optional, freeform JSON — card number, UPI
ID, etc. depending on `method`).

**Response** — `201 Created` with `PaymentResponse`: `id`, `orderId`, `merchantId`, `amount`
(copied from the order), `status`, `method`, `methodDetails`, `errorCode`, `errorDescription`,
`capturedAt`, `createdAt`. **`status`/response body today depends heavily on `method`** — see the
note on `PaymentAdapter` below; `NETBANKING` in particular can come back with an empty body on its
happy path, a currently-open bug (see [Known gaps](gaps.md)).

**Behavior:** locks the order row (`SELECT ... FOR UPDATE` via
`OrderRepository.findByIdAndMerchantIdForUpdate`) to serialize concurrent payment attempts against
the same order; rejects with `404 Not Found` (`ResourceNotFoundException`) if `orderId` doesn't
exist or doesn't belong to the hardcoded merchant, and with `409 Conflict` (`ConflictException`,
code `ORDER_NOT_PAYABLE`) unless the order is `CREATED` or `ATTEMPTED`. On success: sets the order
to `ATTEMPTED` and increments its `attempts`, creates a `Payment` row (`status = CREATED`, a fresh
random `idempotencyKey` — not yet enforced, see [Known gaps](gaps.md)),
and routes the request through `PaymentGatewayRouter` to the method's `PaymentAdapter`. The
returned `PaymentResult` is then applied to the `Payment`: `Pending` sets `processorReference`;
`Failure` sets `status = FAILED` plus `errorCode`/`errorDescription`; `Success` is treated as an
invalid synchronous state and discarded (`return null` — the whole response body). In practice,
per `method`:
- `CARD` — `CardPaymentAdapter` is still a stub, so this falls through `case null` and comes back
  `status: CREATED` unchanged.
- `NETBANKING`/`UPI` — both processors now have real mock logic (`methodDetails.bank ==
  "BANK_CODE_FAIL"` for netbanking, `methodDetails.vpa == "fail@okaxis"` for UPI → `Failure`;
  anything else → `Success`). A request with that failure sentinel comes back `status: FAILED`
  with a generated `errorCode`. Any other request hits the `Success` branch above and **the
  endpoint returns `201 Created` with an empty body** — the `Payment` row is still correctly
  persisted, it's just never reported back. See
  [Known gaps](gaps.md).
