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

## `POST /v1/auth/login`

Authenticates a merchant user and issues a JWT access token.

**Request body** (`LoginRequest`): `email` (required, valid email format), `password` (required).

**Response** — `200 OK` with `LoginResponse`: `accessToken` — a JWT carrying `merchant_id` and
`role` claims, HMAC-signed via `JwtUtil` (60-minute expiry).

**Behavior — still not functional, see [Known gaps](gaps.md) item
12:** `AuthServiceImpl.login` authenticates via `AuthenticationManager.authenticate(new
UsernamePasswordAuthenticationToken(email, password))`, then separately looks up the `AppUser` by
email (`ResourceNotFoundException` if missing) to read its `merchant_id`/`role` for the token.
`WebSecurityConfig` now wires a real `AuthenticationManager` (`DaoAuthenticationProvider` +
`merchant/security/MerchantUserDetailsService`, backed by `AppUserRepository`) and a
`BCryptPasswordEncoder`, so `authenticate()` checks an actual `AppUser` row rather than Spring
Boot's default in-memory user. It still fails for every real merchant, though:
`AuthServiceImpl.signup` never hashes the password before writing it to `AppUser.passwordHash` (see
[Known gaps](gaps.md) item 6), and `BCryptPasswordEncoder` requires
the stored value to already be a bcrypt hash to compare against — a plaintext value never matches.
Also note: even a successful login wouldn't let the returned token do anything yet, since no filter
validates a JWT on subsequent requests (`WebSecurityConfig.jwtChain` still permits all requests
unauthenticated).

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
`Success` today (see below). Each processor's mock logic recognizes several distinct test
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
