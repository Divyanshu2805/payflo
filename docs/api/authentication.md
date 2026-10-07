# Authentication

Signing up a merchant and logging in. **Service:** merchant-service · **Controller:** `AuthController` (`/v1/auth`)

Signup, login and refresh are public routes at the gateway; logout and the password change need a login. merchant-service issues the JWT; the gateway verifies it on every later request — see the [authentication flow](../architecture/flows/authentication.md).

| Method | Path | Request | Response | Notes |
|---|---|---|---|---|
| `POST` | `/v1/auth/signup` | `MerchantSignupRequest { name, email, password, businessName?, businessType? }` | `201` `MerchantResponse { id, name, email, businessName, businessType, merchantStatus }` | Creates the merchant (always `PENDING_KYC`) and its first user (`OWNER`) in one transaction. **An email that is already registered gets the same `201` and the same body shape, with an `id` that belongs to no account, and nothing is created or changed** — see [signup and enumeration](#signup-and-account-enumeration). |
| `POST` | `/v1/auth/login` | `LoginRequest { email, password }` | `200` `LoginResponse { accessToken, refreshToken, expiresInSeconds }` | A JWT carrying `merchant_id`, `role` and its own id, valid 100 minutes (`expiresInSeconds`), and a single-use refresh token valid 7 days. A wrong password and an unknown email both return `401 INVALID_CREDENTIALS` — in the same time, since an unknown email still costs one bcrypt check — so the response never reveals whether an email is registered. After 10 wrong passwords for an email within 15 minutes the login is locked and answers `429 RATE_LIMIT_EXCEEDED` with `Retry-After`, until the window passes or the right password is used. A suspended merchant gets `403 MERCHANT_SUSPENDED`, but only once the password is right. |

## Sessions

| Method | Path | Request | Response | Notes |
|---|---|---|---|---|
| `POST` | `/v1/auth/refresh` | `{ refreshToken }` | `200` `LoginResponse` | A **public** route. Exchanges a refresh token for a new access token and a new refresh token; the old one is used up, so a stolen one works at most once and the legitimate client notices. `401 INVALID_REFRESH_TOKEN` if it is unknown, expired, used, or its user was removed; `403 MERCHANT_SUSPENDED` if the merchant has been suspended since. |
| `POST` | `/v1/auth/logout` | optional `{ refreshToken }` | `204` | Dashboard login only. Revokes the access token you are calling with, by its id, until it would have expired anyway (the gateway refuses it from then on), and the refresh token if given. |
| `POST` | `/v1/auth/password` | `{ currentPassword, newPassword }` | `204` | Dashboard login only. The new password is at least 8 characters and must differ (`400 PASSWORD_UNCHANGED`). A wrong current password is `401 INVALID_CREDENTIALS` and counts towards the login lockout. **Ends every session of this user, including this one**: log in again afterwards. |

Sessions are also ended when an owner changes or removes a user (see [merchant account](merchant-account.md#team-users)): every access token issued to that user before is refused, and their refresh tokens are deleted. If Redis can't be reached the check is skipped for access tokens (they are signed and unexpired) but a logout fails loudly rather than silently doing nothing. There is no "forgot my password": changing a password needs the current one, since a reset needs an email provider.

## Signup and account enumeration

Signup is open to anyone, so if it answered `409` for an email that is registered, anyone could ask "is this email a PayFlo account?" of any address, one request at a time. It doesn't: a signup for an email that already logs in (as an owner or as a team member) is answered exactly like a signup that worked — `201`, the same fields, `merchantStatus: PENDING_KYC` — and the password is hashed either way, so the time doesn't give it away. The `id` in that answer belongs to no account, and nothing is created or changed; the existing account keeps its password.

What this costs: there is no email to say "you already have an account", so a person who forgot they did is told it was created and finds out it wasn't when the new password doesn't log in. **To know whether you have an account, log in** (a wrong password and an unknown email look the same there too). The same race — two signups for one email at the same instant — gets the same answer, whichever the unique index lets in.

One door stays ajar: an owner adding a team member (`POST /v1/merchants/users`) is told `409 DUPLICATE_USER_EMAIL` if the email already logs in anywhere on the platform. That needs a login as an owner, which makes it a much smaller door; closing it takes an invitation flow by email.

## Throttling

Signup and login are public, so the gateway limits them per client address: 120 requests a minute by default (`PUBLIC_AUTH_RATE_LIMIT_PER_MINUTE`), then `429`. Provisioning many merchants from one machine (the [load test](../load-testing/running.md) does) can raise it.

## Validation

| Field | Constraint |
|---|---|
| `name` | required, at most 50 characters |
| `email` | required, a valid email |
| `password` | required, at least 8 characters |
| `businessName` | at most 50 characters |
| `businessType` | one of `LLP`, `PROPRIETORSHIP`, `PARTNERSHIP`, `PRIVATE_LIMITED`, `PUBLIC_LIMITED`, `TRUST` |

## Related

- [API keys](api-keys.md) — credentials for a merchant's backend.
- [merchant-service data model](../schema/merchant-service.md).
