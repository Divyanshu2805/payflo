# API Behavior Worth Knowing

Response behaviour that is easy to mistake for a bug when integrating with the API. All of it is current, intended or at least known behaviour.

## Another merchant's resource is a `404`

Orders, payments, API keys and webhook configs are looked up by id **and** the caller's merchant, so another merchant's id simply isn't found. That also avoids confirming which ids exist.

## A failed payment is still a `201`

`POST /v1/payments` returns `201` whenever a payment row was created — including when the acquirer declined it or the saga compensated. Check `status` and `errorCode`, not just the HTTP status. See [payments](payments.md#failures-that-arent-http-errors).

## A payment starts as `AUTHORIZING`

The response to `POST /v1/payments` is usually `AUTHORIZING`; the simulated bank resolves it seconds later. Read it with `GET /v1/payments/{id}`, or subscribe to `PAYMENT_STATUS_CHANGED` with a [webhook config](webhook-configs.md).

## A business-rule violation is `400`, not `409`

`ORDER_NOT_PAYABLE` is `400`. `409` is reserved for duplicates (`DUPLICATE_USER_EMAIL`, `ORDER_RECEIPT_DUPLICATE`, `DATA_INTEGRITY_VIOLATION`), an illegal state transition, and an idempotency key still in flight.

## Both credentials work everywhere

A JWT can create orders and an API key can manage webhook configs. The exception is the merchant's own profile, payout account, KYC, users and password, which need a dashboard login: an API key gets `403 DASHBOARD_LOGIN_REQUIRED`. And a `TEAM` login is read-only whatever the endpoint.

## The API-key path takes the row id

`DELETE /v1/merchants/api-keys/{id}` and `…/{id}/rotate` take the key's `id` (a UUID from the create or list response), not its `pf_…` `keyId`.

## An idempotency key belongs to one request

A repeated `X-Idempotency-Key` within 24 hours replays the first successful response only when the request is the same one: a different body, query string or path is `422 IDEMPOTENCY_KEY_REUSED`. Use a new key for a new operation. The exceptions are on [idempotency](idempotency-and-rate-limits.md#what-is-deliberately-not-stored-or-hashed): a response with a once-only secret is never replayed (`409`), and card or password bodies are matched by key alone.

## An order takes one payment at a time

An order stays payable while it is `ATTEMPTED`, so that a failed payment can be retried — but only once every earlier attempt has `FAILED`, been `CANCELLED` or `AUTH_EXPIRED`. While another payment for the order is in flight or completed, `POST /v1/payments` is `400 ORDER_PAYMENT_IN_PROGRESS`, with or without an `X-Idempotency-Key`. Still send a key: it is what lets a retry of the *same* request return the original payment instead of that error.

## Error bodies from the gateway are smaller

The gateway's own `401` and `429` carry `errorCode` and `errorDescription` only; a service's errors also carry `timestamp` (and `fieldErrors` for validation).
