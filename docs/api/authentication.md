# Authentication

Signing up a merchant and logging in. **Service:** merchant-service · **Controller:** `AuthController` (`/v1/auth`)

Both endpoints are public routes at the gateway. merchant-service issues the JWT; the gateway verifies it on every later request — see the [authentication flow](../architecture/flows/authentication.md).

| Method | Path | Request | Response | Notes |
|---|---|---|---|---|
| `POST` | `/v1/auth/signup` | `MerchantSignupRequest { name, email, password, businessName?, businessType? }` | `201` `MerchantResponse { id, name, email, businessName, businessType, merchantStatus }` | Creates the merchant (always `PENDING_KYC`) and its first user (`OWNER`) in one transaction. `409 DUPLICATE_MERCHANT_EMAIL` if the email is taken. |
| `POST` | `/v1/auth/login` | `LoginRequest { email, password }` | `200` `LoginResponse { accessToken }` | A JWT carrying `merchant_id` and `role`, valid 100 minutes. A wrong password and an unknown email both return `401 INVALID_CREDENTIALS` — in the same time, since an unknown email still costs one bcrypt check — so the response never reveals whether an email is registered. After 10 wrong passwords for an email within 15 minutes the login is locked and answers `429 RATE_LIMIT_EXCEEDED` with `Retry-After`, until the window passes or the right password is used. A suspended merchant gets `403 MERCHANT_SUSPENDED`, but only once the password is right. |

## Throttling

Signup and login are public, so the gateway limits them per client address: 120 requests a minute by default (`PUBLIC_AUTH_RATE_LIMIT_PER_MINUTE`), then `429`. Provisioning many merchants from one machine (the [load test](../load-testing/running.md) does) can raise it. `signup` still answers `409 DUPLICATE_MERCHANT_EMAIL` for a registered email, which does reveal that it exists — the limit is what stops it being mined at speed.

## Validation

| Field | Constraint |
|---|---|
| `name` | required, at most 50 characters |
| `email` | required, a valid email |
| `password` | required, at least 8 characters |
| `businessName` | at most 50 characters |
| `businessType` | one of `LLP`, `PROPRIETORSHIP`, `PARTNERSHIP`, `PRIVATE_LIMITED`, `PUBLIC_LIMITED`, `TRUST` |

## Not available

There is no refresh token and no logout: when the access token expires, log in again. The monolith's `POST /v1/auth/refresh` and `POST /v1/auth/logout` haven't been ported — see [known gaps](../known-gaps/not-yet-built.md#ported-from-the-monolith).

## Related

- [API keys](api-keys.md) — credentials for a merchant's backend.
- [merchant-service data model](../schema/merchant-service.md).
