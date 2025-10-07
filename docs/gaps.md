# Known gaps vs. requirements / v1 design

[← Back to docs index](README.md)

Found while syncing docs to the actual implementation (2025-10-06) — not yet triaged as intentionally
dropped vs. still planned:

1. `ORDER_RECORD` has no `idempotency_key` — the idempotent-order-creation requirement isn't backed yet.
2. `API_KEY` has no `webhook_secret_hash`.
3. `CUSTOMER` has no `gst_id`.
4. `PAYMENT` has no running `refunded_amount` total (derivable from `REFUND` rows instead).
5. `PAYMENT_TRANSITION_LOG` has no `reason` field.

## VAULT_CARD

Encrypted card data at rest. Never exposed directly — always accessed indirectly via a `CARD_TOKEN`.

| Field | Meaning |
|---|---|
| `id` | Primary key. |
| `encrypted_pan` | The full card number, encrypted — never stored or read in plain text. |
| `encrypted_dek` | The Data Encryption Key used to encrypt the PAN, itself encrypted (envelope encryption — each card gets its own key, and that key is protected by a master key, limiting the damage if one key is ever compromised). |
| `last_four` | Last 4 digits of the card — safe to display without decrypting anything. |
| `brand` | Card network (Visa, Mastercard, etc.). |
| `bin` | Bank Identification Number (first 6–8 digits) — identifies the issuing bank/card type. |
| `expiry_month` / `expiry_year` | Card expiry. |
| `created_at` / `updated_at` | Record lifecycle timestamps. |

## CARD_TOKEN

The opaque, safe-to-reference token that stands in for a vaulted card.

| Field | Meaning |
|---|---|
| `id` | Primary key. |
| `token` | The opaque value merchants/customers actually reference instead of the real card. |
| `vault_card_id` | Links back to the actual encrypted card data. |
| `customer_id` | Which customer this token belongs to. |
| `merchant_id` | Owning merchant — scoping so one merchant can't use another merchant's customer's token. |
| `created_at` / `updated_at` | Record lifecycle timestamps. |

## WEBHOOK_EVENT

A single outbound webhook delivery attempt (and its retry bookkeeping).

| Field | Meaning |
|---|---|
| `id` | Primary key. |
| `merchant_id` | Owning merchant. |
| `event_type` | What happened, e.g. `payment.captured`, `refund.processed`. |
| `payload` | The actual event data sent to the merchant (JSON). |
| `target_url` | Where it was sent — copied from the webhook config at send time, so later config changes don't rewrite history. |
| `status` | Delivery status (pending, delivered, failed, dead-lettered). |
| `attempts` | How many delivery attempts have been made so far. |
| `last_response_code` | HTTP status code returned by the merchant's endpoint on the last attempt. |
| `next_retry_at` | When the next retry is scheduled. |
| `last_retry_at` | When the last retry happened. |
| `created_at` | When the event was created. |
| `delivered_at` | When it was successfully delivered, if it was. |

## DLQ_EVENT

A webhook event that exhausted its retries and was dead-lettered.

| Field | Meaning |
|---|---|
| `id` | Primary key. |
| `webhook_event_id` | The webhook event that exhausted its retries. |
| `merchant_id` | Owning merchant. |
| `final_error` | The error recorded on the last failed attempt, kept for debugging. |
| `moved_at` | When it was moved into the DLQ. |
| `replayed_at` | When (if) it was manually replayed. |

## SETTLEMENT

A payout batch to a merchant's bank account.

| Field | Meaning |
|---|---|
| `id` | Primary key. |
| `merchant_id` | Owning merchant. |
| `gross_amount_paise` | Total payment amount before any deductions. |
| `refund_amount_paise` | Refunds deducted in this settlement. |
| `fee_amount_paise` | Platform fee deducted. |
| `gst_amount_paise` | Tax deducted. |
| `net_amount_paise` | What's actually paid out: gross − refunds − fee − GST. |
| `status` | Settlement batch status. |
| `bank_reference` | Reference from the (mock) bank transfer. |
| `processed_at` | When the payout was processed. |
| `created_at` | When the settlement batch was created. |

## SETTLEMENT_PAYMENT

Join table linking a settlement batch to the individual payments it includes — this is what makes a
payout traceable back to the exact payments it covers (the audit trail mentioned under Settlement
requirements).

| Field | Meaning |
|---|---|
| `settlement_id` | Part of the composite primary key; the settlement batch. |
| `payment_id` | Part of the composite primary key; a payment included in that batch. |
