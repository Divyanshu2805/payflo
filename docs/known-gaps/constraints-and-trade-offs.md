# Constraints and Trade-offs

Structural limits of the current design. None of them is a problem at today's scale; each one shapes *how* the system would have to change to grow or to go live.

## Trust between services is by reachability

The gateway authenticates; the services believe the `X-Merchant-Id` it forwards. That is sound only while nothing but the gateway can reach a business service. On Kubernetes that holds because every other Service is `ClusterIP`; the `/internal/**` endpoints add one shared token on top, which stops a caller that merely has network access but not a pod that holds the secret, and it is the same secret everywhere. Nothing identifies *which* service is calling, and no mTLS or `NetworkPolicy` enforces who may talk to whom. Going live means adding those. See [decision 0003](../architecture/decisions/0003-authenticate-once-at-the-gateway.md).

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

## Throughput is measured, and short of the target

The [requirements](../requirements.md) set 10k TPS, p99 under a second and 99.99% availability. On one laptop, the [load test](../load-testing/results.md) handles about 1,000 requests per second, with payments captured alongside, at a p99 around 250 ms and no failed requests — latency and correctness are met, throughput is about a tenth of the target, capped by the machine. Reaching 10k TPS is a matter of replicas, a larger database and partitioned outbox publishing, each to be re-measured; the availability target is uptime over months and needs the metrics recorded over time, not a single run.
