# API Behavior Worth Knowing

Response behaviour that is easy to mistake for a bug when integrating with the API. All of it is current, intended or at least known behaviour.

## Another merchant's resource is a `404`

Orders, payments, API keys and webhook configs are looked up by id **and** the caller's merchant, so another merchant's id simply isn't found. That also avoids confirming which ids exist.

## A failed payment is still a `201`

`POST /v1/payments` returns `201` whenever a payment row was created — including when the acquirer declined it or the saga compensated. Check `status` and `errorCode`, not just the HTTP status. See [payments](../api/payments.md#failures-that-arent-http-errors).

## A payment starts as `AUTHORIZING`, and there's no endpoint to poll

The response to `POST /v1/payments` is usually `AUTHORIZING`; the simulated bank resolves it seconds later. There is no `GET` for a payment yet, so subscribe to `PAYMENT_STATUS_CHANGED` with a [webhook config](../api/webhook-configs.md).

## A business-rule violation is `400`, not `409`

`ORDER_NOT_PAYABLE` and `INVALID_CREDENTIALS` are `400`. `409` is reserved for duplicates (`DUPLICATE_MERCHANT_EMAIL`, `ORDER_RECEIPT_DUPLICATE`, `DATA_INTEGRITY_VIOLATION`), an illegal state transition, and an idempotency key still in flight. (The monolith used `409` for business rules.)

## Both credentials work everywhere

A JWT can create orders and an API key can manage webhook configs. The monolith split routes between the two; the gateway doesn't.

## The API-key path takes the row id

`DELETE /v1/merchants/api-keys/{id}` and `…/{id}/rotate` take the key's `id` (a UUID from the create or list response), not its `fp_…` `keyId`.

## An idempotent retry replays the first response, whatever you send

A repeated `X-Idempotency-Key` within 24 hours replays the first successful response even if the new request body is different. Use a new key for a new operation.

## Error bodies from the gateway are smaller

The gateway's own `401` and `429` carry `errorCode` and `errorDescription` only; a service's errors also carry `timestamp` (and `fieldErrors` for validation).
