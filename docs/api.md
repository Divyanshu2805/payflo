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
