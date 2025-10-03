# Entities

[← Back to docs index](README.md)

Domain model designed; not yet implemented in code (no JPA entities/migrations exist yet — this is the
target schema).

## Entity Relationship Diagram (v1)

```mermaid
erDiagram
    MERCHANT {
        UUID Id PK
        string name
        string business_name
        string email
        string status
        string gst_id
        string pan
        string settlement_bank_account
        string settlement_ifsc
        string settlement_account_holder_name
        datetime created_at
        datetime updated_at
        string created_by
        string updated_by
    }

    API_KEY {
        UUID id PK
        UUID merchant_id FK
        string key_id UK
        string key_secret_hash
        string webhook_secret_hash
        string environment
        boolean enabled
        datetime last_used_at
        datetime created_at
        datetime rotated_at
        datetime grace_period_expires_at
    }

    APP_USER {
        UUID id PK
        UUID merchant_id FK
        string email UK
        string password_hash
        string role
        datetime createdAt
        datetime updatedAt
    }

    MERCHANT_WEBHOOK_CONFIG {
        UUID id PK
        UUID merchant_id FK
        string target_url
        string event_type_filter
        boolean enabled
        string webhook_secret
        datetime created_at
    }

    CUSTOMER {
        UUID id PK
        UUID merchant_id FK
        string name
        string email
        string phone
        string gst_id
        datetime created_at
        datetime updated_at
    }

    ORDER_RECORD {
        UUID id PK
        UUID merchant_id FK
        string idempotency_key
        long amount_paise
        string status
        int attempts
        jsonb notes
        datetime expires_at
        string created_by
        string updated_by
    }

    PAYMENT {
        UUID id PK
        UUID order_id FK
        UUID merchant_id FK
        string idempotency_key
        long amount_paise
        string status
        string method
        jsonb method_details
        string bank_reference
        string error_code
        string error_description
        long refunded_amount_paise
        datetime captured_at
        datetime settled_at
        datetime created_at
        datetime updated_at
    }

    REFUND {
        UUID id PK
        UUID payment_id FK
        UUID merchant_id FK
        long amount_paise
        string status
        string bank_reference
        string error_code
        string error_description
        datetime processed_at
        datetime created_at
    }

    PAYMENT_TRANSITION_LOG {
        UUID id PK
        UUID payment_id FK
        string from_status
        string to_status
        string event_type
        string actor
        string reason
        datetime occurred_at
    }

    VAULT_CARD {
        UUID id PK
        bytes encrypted_pan
        bytes encrypted_dek
        string last_four
        string brand
        string bin
        int expiry_month
        int expiry_year
        datetime created_at
        datetime updated_at
    }

    CARD_TOKEN {
        UUID id PK
        string token UK
        UUID vault_card_id FK
        UUID customer_id FK
        UUID merchant_id FK
        datetime created_at
        datetime udpated_at
    }

    WEBHOOK_EVENT {
        UUID id PK
        UUID merchant_id FK
        string event_type
        jsonb payload
        string target_url
        string status
        int attempts
        int last_response_code
        datetime next_retry_at
        datetime last_retry_at
        datetime created_at
        datetime delivered_at
    }

    DLQ_EVENT {
        UUID id PK
        UUID webhook_event_id FK
        UUID merchant_id FK
        string final_error
        datetime moved_at
        datetime replayed_at
    }

    SETTLEMENT {
        UUID id PK
        UUID merchant_id FK
        long gross_amount_paise
        long refund_amount_paise
        long fee_amount_paise
        long gst_amount_paise
        long net_amount_paise
        string status
        string bank_reference
        datetime processed_at
        datetime created_at
    }

    SETTLEMENT_PAYMENT {
        UUID settlemnt_id PK, FK
        UUID payment_id PK, FK
    }

    MERCHANT ||--o{ API_KEY : has
    MERCHANT ||--o{ APP_USER : has
    MERCHANT ||--o{ MERCHANT_WEBHOOK_CONFIG : configures
    MERCHANT ||--o{ CUSTOMER : has
    ORDER_RECORD ||--o{ PAYMENT : has
    PAYMENT ||--o{ REFUND : has
    PAYMENT ||--o{ PAYMENT_TRANSITION_LOG : "logged by"
    VAULT_CARD ||--o{ CARD_TOKEN : tokenize
    WEBHOOK_EVENT ||--o{ DLQ_EVENT : "dead-lettered"
    SETTLEMENT ||--o{ SETTLEMENT_PAYMENT : includes
```

> Note: `SETTLEMENT_PAYMENT.settlemnt_id` is transcribed as-drawn in the v1 diagram (likely a typo for
> `settlement_id` — worth fixing before the migration is written).

## Entity summary

| Entity | Purpose |
|---|---|
| `MERCHANT` | A business onboarded onto the platform; owns settlement bank details and KYC fields (GST, PAN). |
| `API_KEY` | Merchant-scoped API credentials (key/secret hash), supports rotation with a grace period. |
| `APP_USER` | A human user (dashboard login) belonging to a merchant, with a role. |
| `MERCHANT_WEBHOOK_CONFIG` | Per-merchant webhook endpoint configuration and event filter. |
| `CUSTOMER` | An end customer of a merchant, referenced by orders/payments/tokens. |
| `ORDER_RECORD` | A payable order created by a merchant; idempotent by `idempotency_key`. |
| `PAYMENT` | A payment attempt against an order; tracks method, status, and settlement/refund amounts. |
| `REFUND` | A refund issued against a payment. |
| `PAYMENT_TRANSITION_LOG` | Audit trail of status transitions for a payment (state machine history). |
| `VAULT_CARD` | Encrypted card data at rest (PAN/DEK encrypted), never exposed directly. |
| `CARD_TOKEN` | A tokenized reference to a vaulted card, scoped to a customer/merchant. |
| `WEBHOOK_EVENT` | An outbound webhook delivery attempt with retry bookkeeping. |
| `DLQ_EVENT` | A webhook event that exhausted retries and was dead-lettered. |
| `SETTLEMENT` | A payout batch to a merchant's bank account (gross/fee/GST/net breakdown). |
| `SETTLEMENT_PAYMENT` | Join entity linking a settlement to the payments it includes. |
