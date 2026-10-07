# What Was Built, and What It Measured

The whole project on one page: the idea, the design decisions and what each one buys, the optimisations and what each one did to the numbers, and how the claims were checked.

## The idea

PayFlo is the backend behind a "Pay now" button: a business signs up, takes payments by card, UPI, net banking or wallet, is told about every change by a signed webhook, and is paid out each night minus a fee and tax.

It was built to answer one question in working code rather than on a whiteboard: **what does it take for a payment system to stay correct when things fail and stay fast when traffic rises?** So the bank is simulated, which lets every flow run on a laptop, and the effort went into the parts that are hard in any real payment system: never losing or duplicating a payment, keeping card numbers in one place, telling merchants reliably, and finding out by measurement where the system slows down.

## The decisions, and what each one buys

| Decision | What it buys | Where it shows |
|---|---|---|
| [Build one application first, then split it](architecture/decisions/0001-monolith-first-then-split.md) | Service boundaries found while moving them was cheap; the split was mostly mechanical | Four services, each owning one domain |
| [A database per service, no foreign keys across them](architecture/decisions/0002-database-per-service.md) | A service can change its schema, or be scaled, without touching the others | Four databases, plain ids between them |
| [Authenticate once, at the gateway](architecture/decisions/0003-authenticate-once-at-the-gateway.md) | One place holds the credential logic and the rate limits; services stay simple | `GatewayAuthFilter`; no security chain in any service |
| [Card data confined to vault-service](architecture/decisions/0004-isolate-card-data-in-vault-service.md) | One small service handles card numbers instead of all of them; each card has its own encryption key | Every other service sees only a token |
| [Events through a transactional outbox](architecture/decisions/0005-transactional-outbox-for-events.md) | A change and its event commit together or not at all: a crash can't lose an event | Zero lost webhooks in the crash tests |
| [Payment initiation as a saga](architecture/decisions/0006-payment-initiation-as-a-saga.md) | No database connection or lock is held across a call to another service; a failed call is compensated | The connection pool stopped running out under load |
| [A validated payment state machine](architecture/decisions/0007-validated-payment-state-machine.md) | An illegal status change is impossible, and every change is logged | No payment stuck or in an impossible state after a crash |
| [All configuration in the repository](architecture/decisions/0008-configuration-in-the-repository.md) | Every setting is versioned and reviewed like code | One `config-repo/`, the same locally and on Kubernetes |

Alongside these: every write accepts an idempotency key, tied to the request and backed by a unique index in the database; webhooks are signed per attempt with a timestamp, so a replayed request fails; sensitive actions are written to an append-only audit log in the same transaction as the change; and card-testing limits sit where the card is handled.

## The optimisations, and what each one did

Everything was measured with the same load test on one laptop, which also ran the load generator, the databases and all seven services. Each row is the same test after the fixes above it. The first run managed 250 requests a second; the last, 1,272.

| Change | Requests a second | p99 | What the metrics had shown |
|---|---|---|---|
| Baseline | 250 | 502 ms | Requests queued inside the gateway: its proxy pool allowed 5 connections per route |
| Gateway proxy pool raised to 200 per route | 84, half failing | 5.7 s | The queue moved downstream: payment-service's 10-connection database pool ran out |
| Pools sized, a 5 s wait for a connection, open breakers answered as `503` | 158 | 5.7 s | Connections were held by the application but idle |
| **Open-Session-In-View turned off** | **717** | 647 ms | Connections had been held across calls to other services; now only inside transactions |
| Outbox published in acknowledged batches | 745 | 611 ms | The outbox backlog drained about 66 times faster |
| Breakers and retries count only remote failures | 837 | 247 ms | A local pool timeout had been opening the breaker for a healthy service |
| Receipt check moved into the persist transaction; PostgreSQL group commit | 1,279 | 160 ms | Order creation no longer held a connection across the customer lookup |
| Outbox poller: an index, one `UPDATE` per batch, its own scheduler thread | 1,247 | 161 ms | Backlog fell from about 117,000 events to under 1,600 |
| Capture made to run alongside the requests instead of after them | 660 | 373 ms | The honest number: the database now does the capture work inside the run |
| PostgreSQL given 2 GB of cache and a larger WAL budget | 963 | 228 ms | Sessions had been queueing on WAL flushes |
| Bank answers resolved 50 per transaction | 756 | 362 ms | 96% of payments answered by the end of the run, up from 56% |
| **Writes removed that the database never needed; webhook pipeline batched** | **1,272** | **171 ms** | See below |

The last row came from reading what the database actually did per payment ([query review](load-testing/query-review.md)):

| What was found | Effect of fixing it |
|---|---|
| Every outbox row was inserted and then immediately updated | A third of the database's time gone; 3.2 updates per payment became 0 |
| Every payment was written three times at initiation | Two writes instead of three |
| The order-expiry sweeper scanned the whole order table every minute | 655 ms became 0.05 ms, with a partial index |
| Three indexes were redundant | Fewer index writes per payment |
| **Together** | **Database time per payment fell from about 5.2 ms to 3.3 ms** |

And the webhook pipeline, which the load test showed delivering only 5.4% of webhooks within 30 seconds:

| What was found | Effect of fixing it |
|---|---|
| The Kafka consumer committed once per event and fell 84,000 events behind in a minute | It takes a whole poll in one transaction |
| The delivery scheduler sent 100 events a second, two transactions each | Batches are claimed once, sent concurrently, and recorded in one `UPDATE` |
| The HTTP client opened a new connection per delivery and the machine ran out of ports | A pooled client that keeps connections alive |
| **Together** | **99.85% of 91,644 webhooks delivered within 30 seconds** |

None of these was visible one request at a time. Each is written up, with its symptom and cause, in [known pitfalls](practices/gotchas/README.md).

## Where it stands

| What | Measured |
|---|---|
| Throughput | About 1,270 requests a second on one laptop, with payments captured alongside |
| Latency | p99 of 171 ms for the slowest request type |
| Errors under load | 0 failed requests in 227,613 |
| Webhooks | 99.85% delivered within 30 seconds of the change |
| Scaling out | 338 → 758 → 999 requests a second on 1, 2 and 3 Kubernetes replicas of the gateway and payment-service (2.2× and 3.0×) |
| Rolling restart under load | 19 of 226,834 requests failed (99.992% succeeded); no payment duplicated or stuck |
| Retries | 33,600 duplicate requests sent under reused idempotency keys; none took effect |

Details: [load-test results](load-testing/results.md), [scaling](deployment/scaling.md).

## How the claims were checked

- **Tests.** 634 unit and integration tests. The integration tests start each service as the real application on real PostgreSQL, Redis and Kafka in containers, and build each schema from its migrations on an empty database ([testing](practices/testing.md)).
- **Crashes and outages.** A script kills a service or stops PostgreSQL, Redis or Kafka while customers are paying, then checks the database. Seven scenarios, eight checks each, all passing: nothing lost, nothing duplicated, nothing stuck, every webhook delivered ([results](reliability/crash-and-outage-tests.md)). It also found a real bug: orders had relied on Redis alone for idempotency.
- **The whole flow.** One command starts everything and drives a merchant's day through the public API, ending with a settlement whose net is gross minus refunds, fee and tax ([the demo](local-development/demo-and-dashboard.md)).

## Where more would come from

The laptop was the limit in these runs: its processors sat at about 96%, shared between the load generator, the databases and the services. Nothing in the design stops the services scaling out, since every scheduled job holds a lock and every state change is row-locked, so the next gains are capacity rather than redesign:

1. **Separate machines.** Run the load generator elsewhere and give each service its own processors. The request path already scaled 3.0× on three replicas.
2. **A bigger database, then more than one.** PostgreSQL takes every write and is the first thing to saturate: faster storage, a connection pooler once replicas multiply the connections, then splitting payment-service's data by merchant.
3. **Outbox publishing in parallel.** More Kafka partitions with a poller for each, or change data capture, so publishing scales with writes.
4. **The simulated bank on every replica.** It answers payments from one replica at a time; letting each replica take its own batch would make capture scale with the request path.
5. **Measure after each step.** Every bottleneck above was found by running the test and reading the dashboard, not by guessing.

The design trade-offs behind all of this, and what the project leaves to a real deployment, are in [design trade-offs and scope](architecture/trade-offs.md).
