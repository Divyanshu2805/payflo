# Design Trade-offs and Scope

What this design accepts in exchange for what it gets, and where the project stops. PayFlo was built to show how a payment backend is designed, run and measured on one machine; it was never meant to carry real money, and this page says what that leaves out.

## Trust between services is by reachability

The gateway authenticates; the services believe the `X-Merchant-Id` it forwards. That is sound only while nothing but the gateway can reach a business service: locally every process binds the loopback interface, and on Kubernetes every Service except the gateway is `ClusterIP`. The `/internal/**` endpoints add one shared token on top, which stops a caller that merely has network access. It is the same secret everywhere, so nothing identifies *which* service is calling, and traffic between services is plain HTTP. A real deployment adds mutual TLS or a service mesh and a `NetworkPolicy`. See [decision 0003](decisions/0003-authenticate-once-at-the-gateway.md).

## Consistency across services is best-effort

There are no distributed transactions. The payment saga compensates when the acquirer can't be reached, settlement is a sequence of short steps that a recovery job finishes, and the outbox guarantees an event is published if and only if its change committed. Events are delivered **at least once**, so consumers must tolerate duplicates, and nothing guarantees their order across topics. What that buys is in the [crash and outage tests](../reliability/crash-and-outage-tests.md): a service killed mid-payment loses and duplicates nothing.

## The bank is simulated

Authorization, capture, refunds and payouts are decided by simulators, not a real acquirer or payout rail. The flows, state machines and failure handling are real; the answers are not, and KYC activates a merchant without checking a document. Swapping in a real acquirer is a new `PaymentProcessor` and `PaymentAdapter` per method and a real callback endpoint in place of `BankCallbackSimulator`.

## Redis is on the request path, and fails open

API-key authentication reads the Redis cache first, and rate limiting, idempotency, sessions, the webhook retry queue and every scheduler's lock live in Redis. Where Redis is only a guard, its absence is treated as "allow": rate limits and the card-testing rules let traffic through, and a logged-out access token is accepted until it expires. Where it protects money, the database is the backstop: orders, payments and refunds each carry a unique idempotency key of their own. Redis also runs with a memory limit and evicts expiring keys first, so the webhook queue is never dropped; under sustained memory pressure that could evict a rate-limit counter or a logout marker early. An outage of the limiter is not allowed to become an outage of the API; the price is that the limits are best-effort.

## One PostgreSQL server

The four databases are separate but share one server, and each service runs one instance by default. Every scheduled job is lock-guarded and state changes are row-locked, so scaling a service out is a replica count ([measured](../deployment/scaling.md)), but the database server is a single point of failure and the first limit on throughput.

## Migrations only go forward

Flyway versions each service's schema and the tests run every migration on an empty database, but a migration is only ever added: undoing one is another migration.

## Secrets have development defaults

The JWT key, vault master key, webhook encryption key, internal token and admin key default to fixed values in `config-repo/`, so the stack starts with no setup. `ENFORCE_STRONG_SECRETS=true` makes a service refuse to start on one. There is no secret store and no key versioning: rotating the vault's master key means re-encrypting every card.

## Scope

The project covers the backend of a payment gateway end to end against a simulated bank. It leaves out what only matters once real money and real users arrive: a real acquirer and reconciliation against a bank's records; a PCI DSS assessment; multi-factor login, email verification and password reset; an identity per platform operator instead of one admin key; scoped API keys; highly available PostgreSQL, Redis and Kafka; alerting and log shipping. None of these changes the shape of the system described in the [decisions](decisions/README.md).

## Performance

What the system does on one laptop, what each optimisation contributed, and where more would come from are in [what was built and what it measured](../project-summary.md) and the [load-test results](../load-testing/results.md).
