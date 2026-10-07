# Merchant Account

The merchant's own profile, payout account, KYC and team. **Service:** merchant-service · **Controllers:** `MerchantProfileController` (`/v1/merchants/me`), `UserController` (`/v1/merchants/users`)

Reading is open to any caller of the merchant. **Changing** anything here needs a **dashboard login** (a JWT) with the right role — an API key gets `403 DASHBOARD_LOGIN_REQUIRED`, because a merchant's backend credential, which could be a leaked secret, has no say in where the money goes.

## Roles

| Role | Can do |
|---|---|
| `OWNER` | Everything. The merchant that signed up; there is exactly one, and it can't be removed or changed. |
| `ADMIN` | Everything on the API, and the profile — but not the payout account, KYC, or managing users. |
| `TEAM` | Read only. The gateway refuses any non-`GET` request from a `TEAM` login with `403 ROLE_FORBIDDEN`. |

## Profile and payout account

| Method | Path | Request | Response | Notes |
|---|---|---|---|---|
| `GET` | `/v1/merchants/me` | — | `200` `MerchantProfileResponse` | The PAN and the payout account number are masked (`XXXXXXXX9012`); they are accepted in full when set and never returned in full. |
| `PUT` | `/v1/merchants/me` | `UpdateProfileRequest` | `200` `MerchantProfileResponse` | `OWNER` or `ADMIN`. Every field optional — only those sent change. |
| `PUT` | `/v1/merchants/me/settlement-bank` | `SettlementBankRequest { accountNumber, ifsc, accountHolderName, currentPassword }` | `200` `MerchantProfileResponse` | `OWNER` only, **and the password again**: changing where the money goes needs more than a valid login. A wrong password is `403 INCORRECT_PASSWORD` and counts towards the login lockout. The account number is stored encrypted. |

| Profile field | Constraint |
|---|---|
| `name`, `businessName` | 1–50 characters |
| `businessType` | one of `LLP`, `PROPRIETORSHIP`, `PARTNERSHIP`, `PRIVATE_LIMITED`, `PUBLIC_LIMITED`, `TRUST` |
| `contactNumber` | 7–20 digits, spaces, `+` or `-` |
| `websiteUrl` | `http(s)://…`, at most 200 characters |
| `gstId` | a valid GSTIN |
| `panId` | a valid PAN (`ABCDE1234F`) |
| `accountNumber` | 9–18 digits |
| `ifsc` | a valid IFSC code (`HDFC0001234`) |

## KYC

`POST /v1/merchants/me/kyc` (`OWNER` only) submits the merchant for KYC. **It is simulated**, like the bank: there is no document check and no reviewer.

- The profile must have `businessName`, `businessType`, `contactNumber` and `panId`, and a payout account — otherwise `400 KYC_INCOMPLETE` names what's missing.
- A complete merchant is activated at once: `PENDING_KYC` → `ACTIVE`, which is what makes it eligible for settlement. Submitting again when already `ACTIVE` changes nothing.
- The PAN `AAAAA0000A` is rejected, for testing the failure path: `400 KYC_REJECTED`.
- A `SUSPENDED` merchant can't be reactivated this way: `400 KYC_NOT_ALLOWED`. Only the platform operator can lift a suspension ([admin API](admin.md)).

## Team users

| Method | Path | Request | Response | Notes |
|---|---|---|---|---|
| `GET` | `/v1/merchants/users` | — | `200` a list of `UserResponse { id, email, role, createdAt }` | `OWNER` or `ADMIN`. |
| `POST` | `/v1/merchants/users` | `CreateUserRequest { email, password, role }` | `201` `UserResponse` | `OWNER` only. `role` is `ADMIN` or `TEAM` (`400 CANNOT_ASSIGN_OWNER` otherwise); the password is at least 8 characters, stored as a bcrypt hash. `409 DUPLICATE_USER_EMAIL` if the email already logs in. |
| `PUT` | `/v1/merchants/users/{userId}/role` | `{ role }` | `200` `UserResponse` | `OWNER` only. Ends that user's sessions so the new role applies from their next login. |
| `DELETE` | `/v1/merchants/users/{userId}` | — | `204` | `OWNER` only. Ends their sessions at once. The owner can't be removed (`400 OWNER_PROTECTED`). |

An email is one login across the whole platform: signup refuses an email already used by a team member, and adding a user refuses one already registered.

## Related

- [Authentication](authentication.md) — login, refresh, logout, password.
- [Security model](../architecture/security-model.md).
- [merchant-service data model](../schema/merchant-service.md).
