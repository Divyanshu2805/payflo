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
| `BusinessRuleViolationException` | 400 | Its own code — `ORDER_NOT_PAYABLE`, `INVALID_CREDENTIALS`, … |
| `HttpMessageNotReadableException` | 400 | `MALFORMED_REQUEST_BODY` |
| `MethodArgumentTypeMismatchException` | 400 | `INVALID_PARAMETER` — e.g. a path id that isn't a UUID |
| `ResourceNotFoundException` | 404 | `<RESOURCE>_NOT_FOUND` — `ORDER_NOT_FOUND`, `PAYMENT_NOT_FOUND`, `APIKEY_NOT_FOUND`, `USER_NOT_FOUND`, … |
| `DuplicateResourceException` | 409 | Its own code — `DUPLICATE_MERCHANT_EMAIL`, `ORDER_RECEIPT_DUPLICATE` |
| `InvalidStateTransitionException` | 409 | `INVALID_STATE_TRANSITION` |
| `IdempotencyConflictException` | 409 | `IDEMPOTENCY_CONFLICT` — the same key is still being processed |
| `DataIntegrityViolationException` | 409 | `DATA_INTEGRITY_VIOLATION` — a unique index caught a duplicate |
| `RateLimitException` | 429 | `RATE_LIMIT_EXCEEDED`, with `Retry-After` |
| `Exception` (anything else) | 500 | `INTERNAL_ERROR` |

## From the gateway

| Status | `errorCode` | When |
|---|---|---|
| 401 | `UNAUTHORIZED` | No `Authorization` header, an unsupported scheme, or a bad, expired, unknown or revoked credential |
| 429 | `RATE_LIMIT_EXCEEDED` | Over the per-API-key limit; `Retry-After` gives the seconds to wait |

## Failures inside a `201`

A payment that the acquirer declines, or that the saga compensates, is not an HTTP error: `POST /v1/payments` returns `201` with `status: FAILED` and an `errorCode`. See [payments](payments.md#failures-that-arent-http-errors).
