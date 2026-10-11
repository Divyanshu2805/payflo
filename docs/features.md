# Features

Everything PayFlo does, by area, with the rules and limits that apply. How each one is built is in the [architecture](architecture/README.md) and the [API reference](api/README.md); the measured figures are on [project metrics](metrics.md).

The bank side is simulated: the acquirer, the bank's authorization callback and the payout bank are stand-ins that answer after a delay, approve a configurable share and fail on documented test values ([mock acquirer](api/mock-acquirer.md)). Everything on PayFlo's side of that line is the real flow.

## Merchants and access

| | |
|---|---|
| **Signup** | `POST /v1/auth/signup` creates the merchant (`PENDING_KYC`) and its `OWNER` user in one transaction. A signup for an email that is already registered gets the same `201`, so signup can't be used to find out which emails have accounts |
| **Login** | Email and password, checked with bcrypt. A JWT valid 100 minutes and a single-use refresh token valid 7 days. A wrong password and an unknown email look the same and take the same time |
| **Lockout** | 10 wrong passwords for an email within 15 minutes locks the login (`429` with `Retry-After`) |
| **Sessions** | Logout revokes the access token at the gateway; a password change, a role change or removing a user ends every session of that user |
| **Roles** | `OWNER` (everything), `ADMIN` (everything except the payout account, KYC and users), `TEAM` (read-only: the gateway refuses any write) |
| **Team** | The owner adds and removes users and changes roles. An email is one login across the platform |
| **Profile and KYC** | Business details, GSTIN and PAN (validated, returned masked). KYC activates a complete merchant (`PENDING_KYC` → `ACTIVE`), which makes it eligible for payouts |
| **Payout account** | Account number, IFSC and holder. Changing it needs the owner's password again; the number is stored encrypted and returned masked |
| **API keys** | `pf_<environment>_<random>` key id and a secret shown once, stored as a bcrypt hash. `TEST` and `LIVE` environments. Revoke at once, or rotate with a grace period of 0 to 24 hours in which the old secret still works |

Two credentials reach the same API: `Authorization: Bearer <jwt>` for a merchant's staff and `Authorization: Basic keyId:secret` for a merchant's backend. Both are verified once, at the gateway. Changing the profile, payout account, KYC, users or password needs a dashboard login, never an API key. See [authentication](api/authentication.md), [merchant account](api/merchant-account.md) and [API keys](api/api-keys.md).

## Orders

- An order records what is being paid for before any money moves: an amount in INR (1 paisa to INR 5,000,000), an optional receipt (unique per merchant), free-form notes and an optional customer.
- A customer block with an email is found or created by merchant and email, and the order carries its id.
- An order expires 15 minutes after creation unless `expiresAt` says otherwise. An expired order can't be paid, and an unpaid one becomes `EXPIRED` within a minute.
- Statuses: `CREATED`, `ATTEMPTED`, `PAID`, `CANCELLED`, `EXPIRED`. Only an unpaid order with no payment in flight can be cancelled.
- Lists are newest first, paged (1 to 100 per page) and filtered by status.

See [orders](api/orders.md).

## Payments

| Method | Details sent | Charged by |
|---|---|---|
| Card | A token from the vault | vault-service, which is the only service that sees the number |
| UPI | A VPA | payment-service |
| Net banking | A bank code | payment-service |
| Wallet | A wallet name | payment-service |

- **A validated state machine.** A payment moves `CREATED` → `AUTHORIZING` → `AUTHORIZED` → `CAPTURING` → `CAPTURED`, then `PARTIALLY_REFUNDED`, `REFUNDED` or `SETTLED`, or ends `FAILED`, `CANCELLED` or `AUTH_EXPIRED`. Every change goes through one service that checks it against a transition table and writes it to a transition log. An illegal change is a `409`, never a silent update.
- **A saga, not a long transaction.** Initiating a payment is two short transactions around the call to the processor. If the processor can't be reached, the payment is failed, the order becomes payable again and the compensation is published as an event. A call that timed out after it was sent is left `AUTHORIZING`, because the charge may have gone through.
- **One live payment per order.** A new attempt is accepted only after every earlier one has failed.
- **Authorization and capture.** The bank's answer arrives asynchronously, within seconds. An approval is captured at once and the order becomes `PAID`. A capture the bank refuses leaves the payment `AUTHORIZED` for the merchant to retry with `POST /v1/payments/{id}/capture`.
- **Timeouts.** A payment the bank never answers fails after 15 minutes; an authorization nobody captures lapses after 60.

See [payments](api/payments.md) and the [payment flow](architecture/flows/payment.md).

## Card vault

- `POST /v1/vault/tokenize` exchanges a card for a token (`tok_…`), the brand, the last four digits and the expiry. The number is validated (13 to 19 digits, Luhn) and never returned.
- **Envelope encryption.** Each card gets its own random AES-256 data key; the number is encrypted with it (AES-GCM) and the data key is wrapped with the master key, which only vault-service holds.
- The CVV is validated and never stored: the table has no column for it.
- A token can be charged only by the merchant that created it.
- **Card numbers stay out of logs.** Fields holding card data are annotated `@MaskedCard`, and a Logback filter masks any Luhn-valid run of 13 to 19 digits in every log message and stack trace.

See [vault](api/vault.md) and [decision 0004](architecture/decisions/0004-isolate-card-data-in-vault-service.md).

## Refunds

- A full or partial refund of a captured payment. The refunds of a payment add up to at most its amount, and pending refunds count, so two simultaneous requests can't both take the last of it.
- A refund starts `PENDING`; the bank answers a few seconds later with `PROCESSED` or `FAILED`.
- The payment becomes `PARTIALLY_REFUNDED`, then `REFUNDED` once the processed refunds reach its amount, and goes back to `CAPTURED` if the only refund is declined.
- A payment that has been paid out (`SETTLED`) can't be refunded.

See [refunds](api/refunds.md).

## Webhooks

- A merchant registers any number of endpoints, each subscribed to chosen event types or to all of them, each with its own signing secret (shown once, stored encrypted, rotatable). An endpoint can be paused and resumed.
- **Eleven events:** `ORDER_CREATED`, `ORDER_CANCELLED`, `ORDER_EXPIRED`, `PAYMENT_CREATED`, `PAYMENT_STATUS_CHANGED`, `PAYMENT_AUTHORIZATION_COMPENSATED`, `REFUND_CREATED`, `REFUND_PROCESSED`, `REFUND_FAILED`, `SETTLEMENT_PROCESSED`, `SETTLEMENT_FAILED`.
- **Signed per attempt.** Every attempt carries `X-PayFlo-Timestamp` and `X-PayFlo-Signature`, an HMAC-SHA256 over `<timestamp>.<body>`. A captured request replayed later fails verification; a retry carries a fresh timestamp and signature and the same body and event id.
- **Retries and a dead-letter queue.** Seven attempts: the first at once, then after 1 minute, 5 minutes, 30 minutes, 2 hours, 8 hours and 24 hours. After the seventh the delivery is `DEAD`.
- **Inspect and replay.** Every delivery is listed with its attempts, last response code and body, and any delivery can be sent again.
- **Safe targets.** A webhook URL must be `https`, carry no credentials and resolve only to public addresses, checked when it is saved and again before every delivery. Redirects are not followed.

See [webhook configs](api/webhook-configs.md), [webhook deliveries](api/webhook-deliveries.md) and the [delivery flow](architecture/flows/webhook-delivery.md).

## Settlement

- Each night at 23:00 every `ACTIVE` merchant with a payout account is paid its captured payments, one settlement per currency.
- `net = gross − refunds − fee − GST`: the platform fee is 2% of what the merchant kept, and GST is 18% of the fee. Both rates and a hold period (T+N days) are settings.
- A settlement is linked to the exact payments it covers, which become `SETTLED` once the bank pays.
- **A recoverable saga.** Settlement is a sequence of short transactions with the remote calls between them. A recovery job finishes one interrupted after any step, and a transfer the bank never answers is failed after 120 minutes so its payments are paid by the next run. No payment is paid out twice.
- A payment with a refund still pending waits for the next run; a fully refunded payment is never paid out.

See [settlements](api/settlements.md) and the [settlement flow](architecture/flows/settlement.md).

## Analytics

- **Dashboard:** today, the last seven days day by day, and captured payments by method.
- **Report:** any range up to 366 days, grouped by day, week or month.
- Each figure has gross, refunded and net amounts, the payments started, the failures and the success rate, read from the database on every call.

See [analytics](api/analytics.md).

## Safe retries

- Every write accepts `X-Idempotency-Key`. A repeat within 24 hours gets the first response replayed and the operation doesn't run again; a repeat while the first is still running is `409`.
- **A key belongs to one request.** Each request is fingerprinted (method, path, query and body); the same key with a different request is `422`.
- Orders, payments and refunds also keep the key in the database under a unique index, so a retry is safe after 24 hours and while Redis is down.
- A response that carries a once-only secret is never stored, and a body holding a card number or a password is never hashed.

See [idempotency and rate limits](api/idempotency-and-rate-limits.md).

## Abuse protection

| Rule | Limit | Answer |
|---|---|---|
| Requests per API key | 200 a minute | `429` with `Retry-After` |
| Requests per merchant, dashboard traffic | 600 a minute | `429` |
| Signup and login per client address | 120 a minute | `429` |
| Failed authentications per client address | 30 a minute, then refused before any credential is checked | `429` |
| Cards tokenized per merchant | 30 a minute and 600 an hour | `429 CARD_TOKENIZATION_LIMIT_EXCEEDED` |
| Card testing | 20 failed card payments in 10 minutes that are at least half of the merchant's card payments | `429 CARD_TESTING_SUSPECTED`, cards only |
| Card payments per order | 5 | `400 ORDER_CARD_ATTEMPTS_EXCEEDED` |
| Request body | 1 MB, refused before authentication | `413 REQUEST_TOO_LARGE` |

A merchant refused for card testing can still take UPI, net banking and wallet payments, and a failure of PayFlo's own (an unreachable vault) is never counted against a card.

## Audit log and the operator

- **Audit log.** Sixteen sensitive actions (payout account, KYC, password, API keys, webhook endpoints and secrets, users, suspension, settlement runs) are recorded with the actor, the client address and the facts, in the same transaction as the change. The table is append-only: a database trigger refuses `UPDATE`, `DELETE` and `TRUNCATE`. No entry holds a secret.
- **Admin API.** The platform operator, with a key of its own that no merchant credential can stand in for, lists merchants, suspends and reactivates one (a suspension applies to the next request), runs a settlement on demand and reads the audit log across merchants.

See [audit log](api/audit-log.md) and [admin API](api/admin.md).

## Operating it

- **One-command demo.** `python demo/demo.py` starts the infrastructure and the seven services, seeds a merchant through the public API (KYC, an API key, webhooks, payments by every method, the failure cases, a refund, a settlement) and serves the dashboard.
- **Dashboard.** A web client over the public API: overview and reports, orders with a checkout, payments, refunds, settlements, webhook endpoints and deliveries, API keys, team, the audit log and the operator's console, with a bar listing every API request the page makes.
- **API reference.** A hand-written OpenAPI 3.0 spec, checked against the controllers in CI, rendered by Swagger UI; and a Postman collection that walks the same API.
- **Observability.** One trace per request across every service in Zipkin, Prometheus metrics with latency histograms, and a Grafana dashboard for throughput, p99, availability, the outbox, payments and the webhook SLA.
- **Kubernetes.** Kustomize manifests for a local kind cluster, and an overlay with three replicas of the gateway and payment-service, rolling updates and disruption budgets.
- **Load, crash and outage tests.** A JMeter plan with a script that reports throughput, latency, capture and the webhook SLA; and a script that kills a service or stops PostgreSQL, Redis or Kafka under load and checks nothing was lost, duplicated or stuck.

See [the demo and the dashboard](local-development/demo-and-dashboard.md), [observability](observability/README.md), [deployment](deployment/README.md), [load testing](load-testing/README.md) and the [crash and outage tests](reliability/crash-and-outage-tests.md).
