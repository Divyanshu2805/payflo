# Requirements

What PayFlo set out to do. Every functional item below is built, against a simulated bank; what each piece does is
in the [architecture](architecture/README.md) and the [API reference](api/README.md), and what the design leaves
out is in [design trade-offs and scope](architecture/trade-offs.md).

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
- **Idempotent order creation** (via `X-Idempotency-Key`) — if a create-order request is retried (say,
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

What the system was built to hold to, and what it measured on one laptop running the load generator, the databases
and every service together. The numbers and how each was obtained are in
[what was built and what it measured](project-summary.md).

| Attribute | Goal | Measured |
|---|---|---|
| Throughput | As much as one machine gives, with the next bottleneck known | About 1,270 requests a second, up from 250 in the first run; 3.0× on three Kubernetes replicas |
| Latency | p99 under 1 second | 171 ms for the slowest request type |
| Availability | No failed requests under load or during a rollout | 0 failed in 227,613 under load; 19 of 226,834 during a rolling restart |
| Durability | No payment lost, even when something crashes | Seven crash and outage scenarios: nothing lost, duplicated or stuck |
| Idempotency | Any write can be retried safely for 24 hours | 33,600 duplicate requests, none took effect |
| Webhook delivery | 99% within 30 seconds, retried for 24 hours | 99.85% within 30 seconds |
| Settlement | Paid out within a day of capture | A nightly run settles every captured payment, net of refunds, fee and tax |
| Security | Card data in one place; webhooks verifiable | Card numbers confined to vault-service and encrypted per card; webhooks HMAC-signed with a timestamp |
| Observability | Failures can be seen and inspected | Every webhook delivery, including dead-lettered ones, is listed and replayable; traces, metrics and a dashboard |
