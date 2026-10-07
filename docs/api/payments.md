# Payments

Paying an order, and capturing an authorized payment. **Service:** payment-service · **Controller:** `PaymentController` (`/v1/payments`)

| Method | Path | Request | Response | Notes |
|---|---|---|---|---|
| `POST` | `/v1/payments` | `PaymentInitRequest { orderId, method, methodDetails? }` + optional `X-Idempotency-Key` header | `201` `PaymentResponse { id, orderId, merchantId, amount, status, method, methodDetails, errorCode, errorDescription, capturedAt, createdAt }` | Usually `AUTHORIZING`; `FAILED` with an `errorCode` for a [test failure value](mock-acquirer.md). `404 ORDER_NOT_FOUND` for an unknown order; `400 ORDER_NOT_PAYABLE` unless the order is `CREATED` or `ATTEMPTED`. `400 ORDER_PAYMENT_IN_PROGRESS` while another payment for the order is in flight or completed (retry only after every earlier attempt has failed). **Card-testing protection** (card payments only, see [velocity limits](idempotency-and-rate-limits.md#velocity-limits-and-card-testing)): `429 CARD_TESTING_SUSPECTED` with `Retry-After` when most of the merchant's recent card payments have been declined, and `400 ORDER_CARD_ATTEMPTS_EXCEEDED` for a sixth card payment on one order. |
| `GET` | `/v1/payments` | `?status=&orderId=&page=&size=` | `200` a page of `PaymentResponse`, newest first | Filter by `status` and/or the order. See the [state machine](../schema/enums.md#payment-state-machine) for the statuses. |
| `GET` | `/v1/payments/{paymentId}` | — | `200` `PaymentResponse` | The way to poll an outcome, alongside the webhooks. `404 PAYMENT_NOT_FOUND` for an unknown or another merchant's payment. |
| `POST` | `/v1/payments/{paymentId}/refunds` | see [Refunds](refunds.md) | `201` `RefundResponse` | Also `GET` the refunds of a payment. |
| `POST` | `/v1/payments/{paymentId}/capture` | — | `200` `PaymentResponse` | Captures an `AUTHORIZED` payment: `CAPTURED` on success (and the order `PAID`), and on a refusal by the bank still `200` with the payment back in `AUTHORIZED` and `errorCode` `CAPTURE_DECLINED` — retry the call (see [capture failures](mock-acquirer.md#3-capture--the-acquirer-can-refuse-it)). A held authorization lapses to `AUTH_EXPIRED` after 60 minutes. Anything not `AUTHORIZED` is `409 INVALID_STATE_TRANSITION`. `404 PAYMENT_NOT_FOUND` for an unknown or another merchant's payment. |

## `methodDetails` by method

| `method` | `methodDetails` | Notes |
|---|---|---|
| `CARD` | `{ "token": "tok_…" }` | A token from [`POST /v1/vault/tokenize`](vault.md). Charged by vault-service — payment-service never sees the card number |
| `UPI` | `{ "vpa": "name@bank" }` | Required |
| `NETBANKING` | `{ "bank": "<code>" }` | Required |
| `WALLET` | `{ "wallet": "PAYTM" }` | Required. A simulated wallet: the value `wallet_fail` is rejected (`WALLET_REJECTED`), anything else is left for the bank callback |

A missing or blank detail — or a `token`, `vpa` or `bank` that isn't a string of at most 200 characters — is `400 INVALID_PAYMENT_DETAILS`, before any payment is created.

`amount` is always copied from the order; it can't be set here.

## What happens after `201`

A payment normally comes back `AUTHORIZING`. Within a few seconds the simulated bank resolves it: it becomes `AUTHORIZED` and is immediately captured to `CAPTURED` (and the order to `PAID`), or it becomes `FAILED` with `SIM_BANK_ERROR_CODE`. Each outcome publishes `PAYMENT_STATUS_CHANGED`, which reaches the merchant as a [webhook](webhook-configs.md). Read it with `GET /v1/payments/{id}`, or subscribe to the webhooks. See the [payment flow](../architecture/flows/payment.md) and the [state machine](../schema/enums.md#payment-state-machine).

## Retrying safely

Send the same `X-Idempotency-Key` to retry: a payment already created under that key for this merchant is returned instead of a second one being made, and the idempotency filter replays the first successful response for 24 hours. The same key for a different order or method is `422 IDEMPOTENCY_KEY_REUSED`, not a replay. Without the header, a retry creates a new payment attempt. See [idempotency](idempotency-and-rate-limits.md#idempotency).

## Failures that aren't HTTP errors

A payment that fails at the acquirer is still a `201`, with `status: FAILED` and an `errorCode`:

| `errorCode` | Meaning |
|---|---|
| `CARD_DECLINED`, `CARD_EXPIRED`, `UPI_REJECTED`, `BANK_REJECTED` | A [test failure value](mock-acquirer.md) |
| `UPI_FAILED`, `NBK_FAILED` | The UPI or net-banking call threw (the cause is logged, not returned) |
| `CARD_TOKEN_INVALID` | The card token is unknown, revoked, or belongs to another merchant |
| `PAYMENT_AUTHORIZATION_TIMEOUT` | The bank didn't answer within 15 minutes (`payment.timeout.authorizing-minutes`); set by the timeout sweeper. The order can be paid again |
| `CAPTURE_TIMEOUT` | The payment was authorized but not captured within 60 minutes; status `AUTH_EXPIRED` |
| `VAULT_CHARGE_FAILED` | vault-service couldn't decrypt or charge the card |
| `PAYMENT_GATEWAY_ROUTER_UNREACHABLE` | The processor could not be reached — vault-service down or its circuit open — and nothing was charged. The saga compensated and published `PAYMENT_AUTHORIZATION_COMPENSATED`. The description is fixed text, never the underlying exception |

A call to the processor that **times out after it was sent** is different: the charge may have gone through, so the payment is not failed. It comes back `AUTHORIZING` like any other, and the bank's answer — or, failing that, the timeout sweeper — settles it. A card charge is never retried automatically for the same reason.

An order whose `expiresAt` has passed can't be paid (`400 ORDER_EXPIRED`); unpaid orders past it become `EXPIRED` within a minute (only looking back 7 days), publishing `ORDER_EXPIRED`.
