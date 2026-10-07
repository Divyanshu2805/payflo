# Refunds

Giving money back on a captured payment. **Service:** payment-service · **Controller:** `RefundController`

| Method | Path | Request | Response | Notes |
|---|---|---|---|---|
| `POST` | `/v1/payments/{paymentId}/refunds` | optional `CreateRefundRequest { amountUnits?, notes? }` + optional `X-Idempotency-Key` | `201` `RefundResponse { id, paymentId, merchantId, amount, status, notes, errorCode, errorDescription, processedAt, createdAt }` | The refund starts `PENDING`; the simulated bank answers a few seconds later. Omit `amountUnits` to refund whatever is left of the payment. |
| `GET` | `/v1/payments/{paymentId}/refunds` | — | `200` a list of `RefundResponse`, oldest first | `404 PAYMENT_NOT_FOUND` for an unknown or another merchant's payment. |
| `GET` | `/v1/refunds` | `?status=&page=&size=` | `200` a page of `RefundResponse`, newest first | `status`: `PENDING`, `PROCESSED`, `FAILED`. |
| `GET` | `/v1/refunds/{refundId}` | — | `200` `RefundResponse` | `404 REFUND_NOT_FOUND` for an unknown or another merchant's refund. |

## Rules

- A payment can be refunded while it is `CAPTURED` or `PARTIALLY_REFUNDED`. Any other status is `400 PAYMENT_NOT_REFUNDABLE`.
- **Once a payment has been paid out (`SETTLED`) it can't be refunded**: `400 PAYMENT_ALREADY_SETTLED`. The money has left; taking it back would need an adjustment to a later payout.
- The refunds of a payment add up to at most its amount, and refunds still `PENDING` count, so two simultaneous requests can't both take the last of it: `400 REFUND_EXCEEDS_PAYMENT`. `amountUnits` is in the payment's currency, between 1 and the amount left.
- Send `X-Idempotency-Key` to make a retry safe. The key is stored with the refund (unique per merchant), so it holds beyond the 24-hour replay window too.

## What happens next

A refund is created `PENDING` and the payment becomes `PARTIALLY_REFUNDED`. A few seconds later `RefundResolver` — the stand-in for the bank's callback — answers (`payment.refund.delay-seconds`, `payment.refund.success-rate`, default 95%):

| Outcome | Refund | Payment |
|---|---|---|
| Approved | `PROCESSED`, with a `bankReference` | stays `PARTIALLY_REFUNDED`, or becomes `REFUNDED` once the processed refunds add up to the full amount |
| Declined | `FAILED`, `errorCode: SIM_REFUND_DECLINED` | back to `CAPTURED`, unless other refunds are pending or done |

Events: `REFUND_CREATED`, `REFUND_PROCESSED` and `REFUND_FAILED` on `refunds.events`, and `PAYMENT_STATUS_CHANGED` whenever the payment's status moves — all reach the merchant as [webhooks](webhook-configs.md).

## Settlement

A payment that was refunded in part is still paid out, **net of the refunds the bank has completed**: the settlement's `refundAmount` records them, and the platform fee is charged only on what the merchant kept. A payment with a refund still waiting on the bank is held back from that night's payout and picked up the next. A fully refunded payment is never paid out. See [settlement](../architecture/flows/settlement.md).

## Related

- [Payments](payments.md), and the [state machine](../schema/enums.md#payment-state-machine).
- [payment-service data model](../schema/payment-service.md#refund).
