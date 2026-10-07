# Settlements

A merchant's payouts. **Service:** operations-service · **Controller:** `SettlementController` (`/v1/settlements`)

| Method | Path | Request | Response | Notes |
|---|---|---|---|---|
| `GET` | `/v1/settlements` | `?status=&page=&size=` | `200` a page of `SettlementResponse`, newest first | `status`: `INITIATED`, `TRANSFER_PENDING`, `PROCESSED`, `FAILED`. |
| `GET` | `/v1/settlements/{settlementId}` | — | `200` `SettlementResponse { id, status, grossAmount, refundAmount, feeAmount, gstAmount, netAmount, bankReference, processedAt, failureReason, createdAt }` | `404 SETTLEMENT_NOT_FOUND` for an unknown or another merchant's settlement. |
| `GET` | `/v1/settlements/{settlementId}/payments` | — | `200` the ids of the payments this payout covers | Read each with `GET /v1/payments/{id}`. |

## How to read one

`netAmount` = `grossAmount` − `refundAmount` − `feeAmount` − `gstAmount`:

- `grossAmount` — the captured payments paid out in this settlement;
- `refundAmount` — what the bank has refunded of them;
- `feeAmount` — the platform fee on what was kept (`gross − refunds`), 2% by default (`settlement.fee-rate`);
- `gstAmount` — GST on the fee, 18% by default (`settlement.gst-rate`).

| `status` | Meaning |
|---|---|
| `INITIATED` | Recorded; the transfer hasn't reached the bank yet (a recovery job restarts it if it never does) |
| `TRANSFER_PENDING` | The bank has the transfer and hasn't answered |
| `PROCESSED` | Paid out. The covered payments become `SETTLED` |
| `FAILED` | The transfer couldn't be started (`TRANSFER_NOT_STARTED`), the bank declined it (`SIM_PAYOUT_DECLINED`) or never answered (`TRANSFER_TIMEOUT`) — see `failureReason`. The merchant is told with a `SETTLEMENT_FAILED` event, and the payments are paid out in a later run |

A merchant gets one settlement per currency, per nightly run, with a very large run split further so each fits in a settlement. A merchant is only settled when it is `ACTIVE` (see [KYC](merchant-account.md#kyc)) and has a payout account; a payment is paid out once it is old enough (`settlement.hold-days`, 0 by default — T+N). `SETTLEMENT_PROCESSED` and `SETTLEMENT_FAILED` are published as [webhooks](webhook-configs.md).

See the [settlement flow](../architecture/flows/settlement.md) for how it works and recovers.
