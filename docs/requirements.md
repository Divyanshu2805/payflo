# Requirements

[← Back to docs index](README.md)

Design target for the system; not yet implemented in code.

## Functional

**Merchant**
- Merchant onboarding, create account, provide KYC
- Login via email + password
- API key generation with show-once secret

**Order Lifecycle**
- Create order (with amount, notes, expiry)
- List orders with pagination and filtering
- Get order by ID
- Order auto-expiry after 15 minutes
- Idempotent order creation (via `X-Idempotent-Header`)

**Payment Lifecycle**
- Initiate payment for an order with method details
- Support for Card, UPI intent, Net Banking, Wallets
- Support for a mock acquirer
- Payment state machine with various statuses

**Refund Lifecycle**
- Allow refund after payment is done
- Payment refund state machine
- Async refund processing via a scheduler

**Webhook Delivery**
- Per-merchant webhook URL configuration
- HMAC-SHA256 signing of payload
- 7-attempt retry system
- DLQ after 7 retries
- Replay API for dead-lettered webhooks

**Settlement**
- Nightly batch settlement
- Fee and GST calculation, total settlement amount calculation
- Settlement record with full audit trail
- Mock bank transfer with chaos (simulated failures)

**Card Vault**
- Tokenization API
- AES-256 encryption of PAN
- Charge-with-token operation
- `@MaskedCard` annotation + Logback filter for log safety

**Analytics**
- Real-time dashboard with revenue today / last 7 days
- Per-merchant analytics filter
- Historical report

**Multi-tenant Security**
- API key auth (Basic auth header) for server-to-server endpoints
- JWT auth for dashboard endpoints
- Per-merchant rate limiting

## Non-Functional

| Attribute | Target |
|---|---|
| Throughput | 10k TPS |
| Latency | p99 < 1 sec |
| Availability | 99.99% |
| Durability | Zero payment loss even in failures |
| Idempotency | 24-hour window, every write API |
| Webhook SLA | 99% delivered within 30 seconds, 100% within 24 hours |
| Settlement | T+1 (within 24 hours of capture) |
| Security | PCI DSS compliant, HMAC-signed webhooks |
| Observability | DLQ events visible/inspectable |
