# Payments

Paying an order, and capturing an authorized payment. **Service:** payment-service · **Controller:** `PaymentController` (`/v1/payments`)

| Method | Path | Request | Response | Notes |
|---|---|---|---|---|
| `POST` | `/v1/payments` | `PaymentInitRequest { orderId, method, methodDetails? }` + optional `X-Idempotency-Key` header | `201` `PaymentResponse { id, orderId, merchantId, amount, status, method, methodDetails, errorCode, errorDescription, capturedAt, createdAt }` | Usually `AUTHORIZING`; `FAILED` with an `errorCode` for a [test failure value](mock-acquirer.md). `404 ORDER_NOT_FOUND` for an unknown order; `400 ORDER_NOT_PAYABLE` unless the order is `CREATED` or `ATTEMPTED`. |
| `POST` | `/v1/payments/{paymentId}/capture` | — | `200` `PaymentResponse` | Captures an `AUTHORIZED` payment: `CAPTURED` on success, back to `AUTHORIZED` (retryable) on failure. Anything not `AUTHORIZED` is `409 INVALID_STATE_TRANSITION`. `404 PAYMENT_NOT_FOUND` for an unknown or another merchant's payment. |

## `methodDetails` by method

| `method` | `methodDetails` | Notes |
|---|---|---|
| `CARD` | `{ "token": "tok_…" }` | A token from [`POST /v1/vault/tokenize`](vault.md). Charged by vault-service — payment-service never sees the card number |
| `UPI` | `{ "vpa": "name@bank" }` | Required |
| `NETBANKING` | `{ "bank": "<code>" }` | Required |
| `WALLET` | — | In the enum but not supported — no adapter exists |

`amount` is always copied from the order; it can't be set here.

## What happens after `201`

A payment normally comes back `AUTHORIZING`. Within a few seconds the simulated bank resolves it: it becomes `AUTHORIZED` and is immediately captured to `CAPTURED` (and the order to `PAID`), or it becomes `FAILED` with `SIM_BANK_ERROR_CODE`. Each outcome publishes `PAYMENT_STATUS_CHANGED`, which reaches the merchant as a [webhook](webhook-configs.md). There is no endpoint to read a payment's status yet, so webhooks are the way to learn the outcome. See the [payment flow](../architecture/flows/payment.md) and the [state machine](../schema/enums.md#payment-state-machine).

## Retrying safely

Send the same `X-Idempotency-Key` to retry: a payment already created under that key for this merchant is returned instead of a second one being made, and the idempotency filter replays the first successful response for 24 hours. Without the header, a retry creates a new payment attempt. See [idempotency](idempotency-and-rate-limits.md#idempotency).

## Failures that aren't HTTP errors

A payment that fails at the acquirer is still a `201`, with `status: FAILED` and an `errorCode`:

| `errorCode` | Meaning |
|---|---|
| `CARD_DECLINED`, `CARD_EXPIRED`, `UPI_REJECTED`, `BANK_REJECTED` | A [test failure value](mock-acquirer.md) |
| `UPI_FAILED`, `NBK_FAILED` | The UPI or net-banking call threw — usually `vpa` or `bank` missing from `methodDetails` |
| `VAULT_CHARGE_FAILED` | vault-service couldn't decrypt or charge the card |
| `PAYMENT_GATEWAY_ROUTER_UNREACHABLE` | The adapter threw — vault-service down, its circuit open, or an unknown or missing card token. The saga compensated and published `PAYMENT_AUTHORIZATION_COMPENSATED` |
