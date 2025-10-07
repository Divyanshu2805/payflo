# Requirements

[← Back to docs index](README.md)

Design target for the system — a plan, not a contract: items here may be dropped or deferred once actual
implementation starts. Implementation has begun (see Entities below for what's actually built); this
section describes intent and hasn't been individually checked off per item yet. See "Known gaps vs.
requirements" under Entities for specific deviations found so far.

## Functional

**Merchant**
- **Onboarding with KYC** — a business signs up and verifies its identity (KYC) before it can accept
  live payments, which keeps the platform compliant and keeps bad actors out.
- **Login via email + password** — merchant staff sign in to a dashboard using standard credentials.
- **API key generation with show-once secret** — merchants get credentials to call the API from their
  own backend; the secret is shown exactly once at creation and can never be retrieved again afterward,
  so it can't leak from an admin screen or support ticket later.

**Order Lifecycle**
- **Create order** (amount, notes, expiry) — the thing a payment is eventually attempted against; it
  records what's being paid for before any money moves.
- **List orders** with pagination and filtering — merchants can browse and search their order history
  without loading the entire table at once.
- **Get order by ID** — fetch a single order's full detail.
- **Auto-expiry after 15 minutes** — an order that never gets paid automatically expires, so a stale
  checkout link can't be used to pay against an old order later.
- **Idempotent order creation** (via `X-Idempotent-Header`) — if a create-order request is retried (say,
  after a network timeout), the same header value returns the original order instead of creating a
  duplicate.

**Payment Lifecycle**
- **Initiate payment** for an order, with method-specific details (e.g. card number, UPI ID).
- **Multiple payment methods** — Card, UPI intent, Net Banking, and Wallets, so customers can pay however
  they prefer.
- **Mock acquirer** — a simulated bank/processor standing in for a real one, so payment flows can be
  built and tested without an actual banking integration.
- **Payment state machine** — a payment moves through a fixed set of statuses with defined valid
  transitions, instead of a free-form status field that could be set to anything from anywhere.

**Refund Lifecycle**
- **Refund allowed once a payment is done** — refunds can only be issued against a payment that's
  actually completed, not a pending or failed one.
- **Refund state machine** — same idea as the payment state machine, applied to refund status.
- **Async refund processing via a scheduler** — refunds are queued and processed by a background job
  rather than inline in the request, since the actual bank-side refund takes time to settle.

**Webhook Delivery**

A webhook is how PayFlo tells a merchant's own system that something happened (a payment succeeded,
a refund was issued, etc.) by calling a URL the merchant registered.

- **Per-merchant webhook URL configuration** — each merchant registers their own endpoint to receive
  event notifications.
- **HMAC-SHA256 signing of payload** — every webhook is cryptographically signed so the merchant's server
  can verify it genuinely came from PayFlo and wasn't forged or tampered with in transit.
- **7-attempt retry system** — if a merchant's endpoint is down or errors out, delivery is retried up to
  7 times instead of giving up after one failure.
- **Dead-letter queue (DLQ) after 7 retries** — an event that still fails after all retries is moved
  aside instead of being retried forever or silently dropped.
- **Replay API** — once a merchant fixes whatever was wrong with their endpoint, a dead-lettered event
  can be redelivered on demand.

**Settlement**

Settlement is the process of actually paying merchants the money they've earned.

- **Nightly batch settlement** — payments are grouped and paid out together once a day, rather than
  transferring money one payment at a time.
- **Fee and GST calculation** — the payout amount accounts for the platform's fee and applicable tax,
  not just the raw payment total.
- **Full audit trail** — every settlement is fully traceable back to the payments and amounts it covers.
- **Mock bank transfer with chaos** — a simulated bank transfer that can randomly fail or delay, used to
  test how the system behaves under real-world bank unreliability rather than an always-succeeds mock.

**Card Vault**

The card vault is where customer card details are stored so they don't need to be re-entered (or
re-transmitted) on every payment.

- **Tokenization API** — a real card number is exchanged for a token; the token can be used for future
  charges without ever handling the actual card number again.
- **AES-256 encryption of PAN** — the card number (PAN) itself is encrypted at rest, not stored in plain
  text, even inside the platform's own database.
- **Charge-with-token** — a customer can pay using a previously stored token instead of entering full
  card details again.
- **`@MaskedCard` annotation + Logback filter** — a code-level safeguard that guarantees card numbers
  can never accidentally end up in application logs, even if a developer forgets to mask one manually.

**Analytics**
- **Real-time dashboard** — merchants can see today's revenue and the last 7 days at a glance.
- **Per-merchant analytics filter** — analytics can be scoped to one specific merchant.
- **Historical report** — reporting that goes back further than the rolling 7-day dashboard view.

**Multi-tenant Security**

"Multi-tenant" means many merchants share the same PayFlo system, each seeing only their own data.

- **API key auth (Basic auth header)** — used for server-to-server calls, i.e. a merchant's own backend
  calling into PayFlo directly.
- **JWT auth** — used for the dashboard, i.e. a human logging in through a browser.
- **Per-merchant rate limiting** — one merchant sending too much traffic can't degrade the service for
  everyone else.

## Non-Functional

| Attribute | Target | What it means |
|---|---|---|
| Throughput | 10k TPS | The system should handle 10,000 transactions per second at peak load. |
| Latency | p99 < 1 sec | 99% of requests finish in under 1 second — the slow 1% is what this bounds. |
| Availability | 99.99% | Roughly 52 minutes of downtime allowed per year. |
| Durability | Zero payment loss, even during failures | No payment should ever be lost or left in an unknown state, even if a server crashes mid-request. |
| Idempotency | 24-hour window, every write API | Any write request can be safely retried within 24 hours without creating a duplicate side effect. |
| Webhook SLA | 99% delivered within 30 seconds, 100% within 24 hours | Merchant notifications should normally be near-instant, with a hard guarantee they arrive within a day. |
| Settlement | T+1 (within 24 hours of capture) | Merchants get paid out within a day of a payment being captured. |
| Security | PCI DSS compliant, HMAC-signed webhooks | Meets the payment card industry's security standard; webhook payloads are independently verifiable. |
| Observability | DLQ events visible/inspectable | Whoever's on support/ops can see what failed and why, not just that something failed somewhere. |
