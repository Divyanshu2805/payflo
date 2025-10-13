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

**Behavior:** rejects with a `RuntimeException` (currently surfaces as `500`, not a proper `409`) if
`email` already exists on a `Merchant`. Creates the `Merchant` (status forced to `PENDING_KYC`,
ignoring any status sent by the caller — there isn't one, since `MerchantSignupRequest` has no
status field) then the `AppUser` (`role = OWNER`), both in one `@Transactional` method.
