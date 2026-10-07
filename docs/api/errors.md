# Errors

Every error response from every service has the same JSON shape, produced by one handler — `common-lib`'s `GlobalExceptionHandler` — and the gateway answers its own errors in the same shape:

```json
{
  "errorCode": "ORDER_NOT_PAYABLE",
  "errorDescription": "Order is not in a payable state",
  "timestamp": "2026-02-09T14:27:16",
  "fieldErrors": null
}
```

| Field | Present | Meaning |
|---|---|---|
| `errorCode` | Always | A stable, machine-readable code — branch on this and the HTTP status |
| `errorDescription` | Always | Human-readable; don't parse it |
| `timestamp` | Service errors | When it happened. The gateway's own `401`/`429` bodies carry only `errorCode` and `errorDescription` |
| `fieldErrors` | `400 VALIDATION_FAILED` only | `[{ field, message }]` for every invalid field |

## Exception mapping

| Exception | Status | `errorCode` |
|---|---|---|
| `MethodArgumentNotValidException` | 400 | `VALIDATION_FAILED`, with `fieldErrors` |
| `BusinessRuleViolationException` | 400 | Its own code — `ORDER_NOT_PAYABLE`, `INVALID_DATE_RANGE` (an analytics report), … |
| `InvalidCredentialsException` | 401 | `INVALID_CREDENTIALS` — login with an unknown email or a wrong password (deliberately indistinguishable) |
| `AccountSuspendedException` | 403 | `MERCHANT_SUSPENDED` — login or refresh for a suspended merchant |
| `ForbiddenException` | 403 | Its own code — `ROLE_FORBIDDEN` (the role can't do this), `DASHBOARD_LOGIN_REQUIRED` (an API key tried a dashboard-only action), `INCORRECT_PASSWORD` |
| `InvalidTokenException` | 401 | `INVALID_REFRESH_TOKEN` — a refresh token that is unknown, expired or already used |
| `NoResourceFoundException` | 404 | `ROUTE_NOT_FOUND` — no such endpoint (this used to be a `500`) |
| `HttpRequestMethodNotSupportedException` | 405 | `METHOD_NOT_ALLOWED`, with an `Allow` header |
| `HttpMediaTypeNotSupportedException` | 415 | `UNSUPPORTED_MEDIA_TYPE` — send `application/json` |
| `MissingServletRequestParameterException` | 400 | `MISSING_PARAMETER` |
| `HttpMessageNotReadableException` | 400 | `MALFORMED_REQUEST_BODY` |
| `MethodArgumentTypeMismatchException` | 400 | `INVALID_PARAMETER` — e.g. a path id that isn't a UUID |
| `ResourceNotFoundException` | 404 | `<RESOURCE>_NOT_FOUND` — `ORDER_NOT_FOUND`, `PAYMENT_NOT_FOUND`, `APIKEY_NOT_FOUND`, … |
| `DuplicateResourceException` | 409 | Its own code — `DUPLICATE_USER_EMAIL`, `ORDER_RECEIPT_DUPLICATE`, `SETTLEMENT_RUN_IN_PROGRESS`. (Signup no longer answers `DUPLICATE_MERCHANT_EMAIL`: that confirmed an email was registered; see [authentication](authentication.md#signup-and-account-enumeration).) |
| `ServiceUnavailableException` | 503 | Its own code — `AUDIT_LOG_UNAVAILABLE`: an admin action refused because it couldn't be recorded; nothing was done, try again |
| `VelocityLimitException` | 429 | Its own code, with `Retry-After` — `CARD_TOKENIZATION_LIMIT_EXCEEDED`, `CARD_TESTING_SUSPECTED`: the pattern of someone testing card numbers. See [velocity limits](idempotency-and-rate-limits.md#velocity-limits-and-card-testing). (A fifth card payment on one order is a plain `400 ORDER_CARD_ATTEMPTS_EXCEEDED`.) |
| `InvalidStateTransitionException` | 409 | `INVALID_STATE_TRANSITION` |
| `IdempotencyConflictException` | 409 | `IDEMPOTENCY_CONFLICT` — the same key is still being processed |
| `IdempotencyKeyReusedException` | 422 | `IDEMPOTENCY_KEY_REUSED` — the key was already used for a different request |
| `IdempotencyResponseUnavailableException` | 409 | `IDEMPOTENT_RESPONSE_NOT_REPLAYABLE` — the request already succeeded, but its response held a secret that is never stored |
| `DataIntegrityViolationException` | 409 | `DATA_INTEGRITY_VIOLATION` — a unique index caught a duplicate |
| (the gateway) | 413 | `REQUEST_TOO_LARGE` — the request body is over `app.security.max-request-body-bytes` (1 MB); refused before authentication |
| `RateLimitException` | 429 | `RATE_LIMIT_EXCEEDED`, with `Retry-After` — also a login locked after too many wrong passwords |
| `CallNotPermittedException` (payment-service) | 503 | `DEPENDENCY_UNAVAILABLE`, with `Retry-After: 10` — a circuit breaker to merchant- or vault-service is open; retry shortly |
| `Exception` (anything else) | 500 | `INTERNAL_ERROR` |

## From the gateway

| Status | `errorCode` | When |
|---|---|---|
| 401 | `UNAUTHORIZED` | No `Authorization` header, an unsupported scheme, or a bad, expired, unknown or revoked credential |
| 403 | `MERCHANT_SUSPENDED` | The credential is valid but the merchant is suspended (takes effect within a minute) |
| 403 | `ROLE_FORBIDDEN` | A `TEAM` login tried to change something; it is read-only |
| 429 | `RATE_LIMIT_EXCEEDED` | Over the per-API-key limit, the per-merchant limit for dashboard (JWT) traffic, the per-address limit on signup/login, or too many failed authentications from one address; `Retry-After` gives the seconds to wait |

## Failures inside a `201`

A payment that the acquirer declines, or that the saga compensates, is not an HTTP error: `POST /v1/payments` returns `201` with `status: FAILED` and an `errorCode`. See [payments](payments.md#failures-that-arent-http-errors).
