# Constraints and Trade-offs

Structural limits of the current design. None of them is a problem at today's scale; each one shapes *how* the system would have to change to grow or to go live.

## Trust between services is by reachability

The gateway authenticates; the services believe the `X-Merchant-Id` it forwards, and `/internal/**` endpoints trust any caller. That is sound only while nothing but the gateway can reach a business service. On Kubernetes that holds because every other Service is `ClusterIP`, but no credential, mTLS or `NetworkPolicy` enforces it between pods. Going live means adding one of those. See [decision 0003](../architecture/decisions/0003-authenticate-once-at-the-gateway.md).

## Consistency across services is best-effort

There are no distributed transactions. The payment saga compensates when the acquirer can't be reached, and the outbox guarantees an event is published if and only if its change committed, but a crash between two services' writes — settlement marking payments settled, for example — can leave them briefly or permanently out of step. Events are delivered **at least once**, so consumers must tolerate duplicates, and nothing guarantees their order across topics.

## The bank is simulated

Authorization, capture and payouts are decided by simulators, not a real acquirer or payout rail. The flows, state machines and failure handling are real; the answers are not. Capture always succeeds, and the payout callback always succeeds. Swapping in a real acquirer is a new `PaymentProcessor` / `PaymentAdapter` per method and a real callback endpoint in place of `BankCallbackSimulator`.

## Redis is on the authentication path

API-key authentication reads the Redis cache first, and rate limiting, idempotency, the webhook retry queue and every scheduler's lock live in Redis. The gateway falls back to merchant-service on a cache miss, but Redis is a hard dependency of the system as a whole.

## One PostgreSQL server, one replica each

The four databases are separate but share one server, and every service runs one instance. Every scheduled job is ShedLock-guarded and the state machine and row locks are safe under concurrency, so scaling out a service is a replica count — but the database server is a single point of failure.

## The schema is managed by Hibernate

`ddl-auto: update` adds columns and tables but never removes or renames them and keeps no history, so the schema can drift from the entities and there is no rollback. Fine for development; a real deployment needs Flyway or Liquibase first.

## Design targets are not measured

The [requirements](../requirements.md) set targets — 10k TPS, p99 under a second, 99.99% availability. Nothing has been load-tested and there is no metrics pipeline, so none of them is demonstrated today.
