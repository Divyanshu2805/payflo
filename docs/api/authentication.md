# Authentication

Signing up a merchant and logging in. **Service:** merchant-service · **Controller:** `AuthController` (`/v1/auth`)

Both endpoints are public routes at the gateway. merchant-service issues the JWT; the gateway verifies it on every later request — see the [authentication flow](../architecture/flows/authentication.md).

| Method | Path | Request | Response | Notes |
|---|---|---|---|---|
| `POST` | `/v1/auth/signup` | `MerchantSignupRequest { name, email, password, businessName?, businessType? }` | `201` `MerchantResponse { id, name, email, businessName, businessType, merchantStatus }` | Creates the merchant (always `PENDING_KYC`) and its first user (`OWNER`) in one transaction. `409 DUPLICATE_MERCHANT_EMAIL` if the email is taken. |
| `POST` | `/v1/auth/login` | `LoginRequest { email, password }` | `200` `LoginResponse { accessToken }` | A JWT carrying `merchant_id` and `role`, valid 100 minutes. Wrong password: `400 INVALID_CREDENTIALS`. Unknown email: `404 USER_NOT_FOUND`. |

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
