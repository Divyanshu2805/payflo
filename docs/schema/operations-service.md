# operations-service data model

Webhook deliveries and their dead letters, settlements, and this service's own outbox. Database: `payflo_operations`.

![operations-service entity-relationship diagram](../assets/diagrams/er-operations.png)

## WEBHOOK_EVENT

One delivery of one domain event to one merchant endpoint, with its retry bookkeeping.

| Field | Meaning |
|---|---|
| `merchant_id` | Plain id → merchant-service. |
| `event_type` | The domain event, e.g. `PAYMENT_STATUS_CHANGED`. |
| `payload` | The body sent to the merchant, `jsonb`. |
| `target_url` | Copied from the webhook config when the event was created, so a later config change never rewrites history. |
| `signature` | The HMAC-SHA256 signature sent as `X-PayFlo-Signature`. |
| `status` | `WebhookEventStatus` — see [delivery status](enums.md#delivery-status). |
| `attempts` | Delivery attempts so far; the seventh failure dead-letters it. |
| `next_retry_at` | When the next attempt is due. Mirrors the Redis retry queue, so a lost queue entry can be rebuilt. |
| `last_attempt_at`, `last_response_code`, `last_response_body` | The last attempt and what the merchant's endpoint answered — for debugging. |
| `delivered_at` | When a `2xx` came back. |

## DLQ_EVENT

A webhook that exhausted its retries, or a Kafka record that couldn't be turned into one.

| Field | Meaning |
|---|---|
| `merchant_id` | Plain id → merchant-service. |
| `webhook_event_id` | One-to-one FK → `webhook_event`; `null` when processing failed before a webhook event existed. |
| `final_error` | The last error recorded. |
| `payload` | The event data, preserved for a replay. |
| `moved_at` | When it was dead-lettered. |
| `replayed_at` | When it was replayed — there is no replay endpoint yet. |

## SETTLEMENT

One merchant's payout from one nightly run.

| Field | Meaning |
|---|---|
| `merchant_id` | Plain id → merchant-service. |
| `gross_amount_*` | The sum of the settled payments. |
| `refund_amount_*` | Refunds to deduct — not computed yet (see [known gaps](../gaps.md)). |
| `fee_amount_*` | The platform fee, 2% of gross. |
| `gst_amount_*` | GST, 18% of the fee. |
| `net_amount_*` | What is paid out: gross − fee − GST. |
| `status` | `SettlementStatus` — see [settlement status](enums.md#settlement-status). |
| `bank_reference` | The transfer reference returned by the (mock) bank. |
| `processed_at` | When the bank confirmed the payout. |
| `failure_reason` | Why it failed, if it did. |

Each amount is its own `Money` pair (`*_units`, `*_currency`).

## SETTLEMENT_PAYMENT

Links a settlement to each payment it pays out — the audit trail from a payout back to the exact payments it covers.

| Field | Meaning |
|---|---|
| `settlement_id` | Part of the composite key (`SettlementPaymentId`); FK → `settlement`. |
| `payment_id` | Part of the composite key; plain id → payment-service `payment`. |

## OUTBOX_EVENT

Same shape as [payment-service's](payment-service.md#outbox_event), for `SETTLEMENT` events — `SETTLEMENT_PROCESSED` and `SETTLEMENT_FAILED`, published to `settlements.events`.
