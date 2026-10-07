# Results

Measured on a single development laptop (32 logical cores, 16 GB for Docker), with everything on the same machine: JMeter, the seven services started with `java -jar`, PostgreSQL, Redis and Kafka in Docker, and the observability stack. Each run is 100 virtual users against the gateway, following [running a load test](running.md), with the rate limit raised and tracing sampled at 10%.

## Where it stands

| Measure | Goal | Measured |
|---|---|---|
| Throughput | Reported, not graded | **~1,270 req/s** for 3 minutes (≈ 636 orders + 636 payments per second), with every payment answered by the bank and captured alongside (96% of them before the run ends); 1,023 req/s with every merchant also receiving a webhook for each payment |
| p99 latency | < 1 s | **171 ms** for the slowest request type (payments), 245 ms with webhooks |
| Errors | None | **0 failed requests** in 227,613 (183,141 with webhooks) |
| Webhook delivery | 99% within 30 s | **99.85%** of 91,644 events within 30 s, p99 under 2 s, with a webhook per payment (it was 5.4%, see [below](#webhooks-under-load)) |

Latency and correctness hold with margin. Throughput is five times the first run's, and on this setup it is capped by the machine rather than by any one service: total CPU sits at ~96% of 32 cores, shared between the Docker VM (PostgreSQL alone ~5 cores), JMeter, payment-service, Docker Desktop's network proxy (~4 cores) and the other JVMs.

**What the throughput figure includes.** Earlier runs reached 1,279 req/s, but that was the rate at which requests were *accepted*: the bank simulator was resolving payments so slowly that nearly every payment was still `AUTHORIZING` when the run ended and its capture happened afterwards. When the simulator was made to keep up with the run, that work moved inside it, and 56% answered by the end of the run was the best this laptop managed at ~880 req/s. Resolving payments in batches and removing writes the database didn't need (see [the review](query-review.md)) lifted both: ~1,270 req/s accepted *and* 96% answered by the end of the run (what is left is the simulated bank's own 1–10 s delay). The error count is for the run only: availability proper is uptime over months, which a load test can't show (see [what is measured, and how](README.md#what-is-measured-and-how)).

## How it got there

The runs found a series of problems, each visible only under concurrency; counting each separate change in the table below (run 14 bundles seven), that is **19 fixes**. Every row is the same test after the fixes above it. Runs 1–8 measure accepted requests with capture deferred; from run 9 capture runs alongside:

| Run | Change | Throughput | p99 (worst type) | Errors | What the metrics showed |
|---|---|---|---|---|---|
| 1 | Baseline | 250 req/s | 502 ms | 0% | payment-service answered `/v1/orders` in ~25 ms but the gateway took ~370 ms — requests were queueing inside the gateway |
| 2 | Gateway proxy pool raised from 5 to 200 connections per route | 84 req/s | 5.7 s | 50% | With the gateway no longer throttling, 100 concurrent requests reached payment-service: its 10-connection database pool ran out, requests waited 30 s, and the resulting failures opened the merchant-service circuit breaker |
| 3 | Pools sized (payment 40), 5 s acquisition timeout, open breaker answered as `503` | 158 req/s | 5.7 s | 54% | Still out of connections — PostgreSQL showed the pool's connections **idle**, held by the application but not querying |
| 4 | Open-Session-In-View turned off | 717 req/s | 647 ms | ~0% | Connections now held only inside transactions. The outbox backlog peaked at ~89,000 events and drained at ~10/s |
| 5 | Outbox published in acknowledged batches (and the simulator bounded) | 745 req/s | 611 ms | 1.2% | Backlog drained ~66× faster. One burst of 503s at the end of ramp-up: a DB-pool timeout had counted as a merchant-service failure and opened its breaker |
| 6 | Breakers and retries count only remote failures; wider breaker window | 837 req/s | 247 ms | 0% | Order creation still held a database connection across the customer lookup: its receipt check ran before the Feign call |
| 7 | Receipt check moved into the persist transaction; PostgreSQL group commit (`commit_delay`) | 1,279 req/s | 160 ms | 0% | ~117,000 outbox events still unpublished minutes after the run |
| 8 | Outbox poller: index for its query, one `UPDATE` per batch, a scheduler thread pool | 1,247 req/s | 161 ms | 0% | Outbox backlog under ~1,600. The webhook consumer's Kafka lag grew to ~194,000, and 322,000 payments sat in `AUTHORIZING` |
| 9 | Webhook targets cached for 30 s; simulator resolves 16 payments at once, with an index for its query | 660 req/s | 373 ms | 0% | Consumer lag ~0. Capture now runs during the test: payment-service's pool was full with up to 63 requests waiting, and PostgreSQL was the constraint |
| 10 | PostgreSQL given 2 GB of cache and a larger WAL budget (defaults: 128 MB, 1 GB) | 963 req/s | 228 ms | 0% | Sessions mostly waiting on the WAL flush; the disk manages ~470 flushes a second (`pg_test_fsync`) |
| 11 | Time-ordered ids (UUID v7) in payment-service | 1,005 req/s | 267 ms | 0% | Within run-to-run variation of run 10 |
| 12 | Re-run after the security, audit and idempotency work (the baseline for what followed) | 884–920 req/s | 255 ms | 0% | 56% of payments answered when the run ended; backlog peak 34,600, cleared 51 s after. The 5–10% fall from run 11 is the cost of the audit log, the velocity guards and the request fingerprinting |
| 13 | Bank answers resolved 50 per transaction, polled every second | 756 req/s | 362 ms | 0% | 96.5% answered by the end (backlog peak 2,400, cleared in 13 s). Fewer requests accepted: the database now does inside the run the capture work it used to leave for afterwards |
| 14 | Writes removed that the database never needed (an outbox `UPDATE` after every `INSERT`, a second write of every payment), the order-expiry sweeper's full scan replaced by a partial index, three redundant indexes dropped; webhook consumer and delivery batched; pooled webhook client | **1,272 req/s** | **171 ms** | **0%** | 96.3% answered by the end, 505 captures a second. [The review](query-review.md) has the evidence for each change |

Details of each cause and fix are in [known pitfalls](../practices/gotchas/README.md): the [gateway's proxy pool](../practices/gotchas/microservices.md#the-gateways-proxy-pool-allows-5-connections-per-route), [Open-Session-In-View](../practices/gotchas/spring-and-jpa.md#open-session-in-view-holds-a-connection-across-remote-calls), [pool sizing](../practices/gotchas/spring-and-jpa.md#size-the-connection-pool-and-fail-fast-when-its-exhausted); and in [service communication](../architecture/service-communication.md) for the outbox and the breaker scope.

## What's still limiting

- **The machine.** With the load generator on the same host, adding virtual users adds latency, not throughput. A real measurement puts JMeter on separate machines.
- **Capture against the same database.** A payment is still four commits — create the order, record the payment, apply the gateway result, authorize-and-capture — and capture is three state changes, two updates and an event, so it takes its share of the database in the run rather than after it. It now keeps up (96% answered by the end of the run, the rest being the simulated bank's own delay), at the price of that share: the [batched resolver](#capture-under-load) trades the per-payment round trips for batch writes, but a payment's index entries and WAL still have to be written.
- **payment-service's database connections.** All 40 are in use for the whole run, with 65–76 requests waiting at the peak. Sampled in PostgreSQL, about half of them are idle inside a transaction — waiting for the application's next statement, which on a saturated machine, through Docker Desktop's port proxy, is slow to arrive — and most of the rest are waiting for the WAL flush.
- **The disk under Docker Desktop.** ~470 `fdatasync` calls a second (2.1 ms each), so commits are flushed in groups. Removing `commit_delay`, and turning off the pool's auto-commit round trips, each changed nothing measurable (1,023 and 947 req/s) and were not kept.
- **Webhooks and the outbox keep up.** The outbox published ~1,500 events a second (274,547 rows in one 3-minute run with webhooks, from `pg_stat_statements`), its backlog stays under ~1,500 events, and with a webhook per payment 99.85% of deliveries reach the merchant within 30 s ([below](#webhooks-under-load)). Subscribing a merchant to *every* event (three deliveries per payment) is the harder case, measured below.
- **The dataset.** These runs were made on top of 2 to 2.7 million orders and payments left by earlier runs; a fresh database was not measured.

## Capture under load

How many of a run's payments the simulated bank had answered, and captured, when the load stopped (measured by `run_load_test.py` from the payment transition counters, 100 users, 3 minutes):

| | Before | After |
|---|---|---|
| Payments answered by the end of the run | 56.0% | **96.3–96.9%** |
| Backlog at its peak | 34,629 | 3,588–4,327 |
| Time for the backlog to clear after the run | 51 s | 14–17 s |
| Captures a second during the run | 212 | **411–505** |

What remains at the end is the simulated bank's own delay (1 to 10 seconds by method) at 400–500 payments a second: a few thousand payments are always in flight, and it doesn't grow. The change is `BankCallbackSimulator` resolving payments **50 per transaction** (`PaymentServiceImpl.resolveAuthorizations`: one lock query, one query for the orders, JDBC-batched writes, one commit) with 4 batches at once, instead of one transaction and four to eight round trips per payment, and polling every second instead of every five. A failed batch is retried one payment at a time. The card-testing counter for a declined card is now updated after the commit, so a Redis call never holds a batch's locks.

## Webhooks under load

The requirement is that 99% of webhooks are delivered within 30 seconds of the change that caused them. Measured from the change (the outbox row's creation time) to the merchant's endpoint answering `2xx`, with every one of 50 merchants subscribed, over every delivery made during a 3-minute run and the wait for the pipeline to drain:

| | Before | After | After, every event subscribed |
|---|---|---|---|
| Subscribed to | `PAYMENT_STATUS_CHANGED` | `PAYMENT_STATUS_CHANGED` | `ALL` (order, payment created and payment changed) |
| Deliveries | 26,259 delivered by the end of the wait, 7,695 more still waiting, and tens of thousands not yet read from Kafka (lag 84,572 on `payments.events` alone, midway through) | 91,644 | **283,486** (~1,575 a second) |
| **Delivered within 30 s** | **5.4%** | **99.85%** | **100.0%** |
| Latency p50 / p99 (at most) | 5 min / 1 h | 1 s / 2 s | 2 s / 2 s |
| Oldest event waiting for its first attempt | 133 s and growing | 0.3 s | 0.4 s |
| Requests a second / p99 | 794 / 286 ms | 1,023 / 245 ms | 1,039 / 275 ms |

Before, three things capped it, one behind the other:

1. **The consumer.** `WebhookKafkaConsumer` handled one Kafka record at a time, with a commit for each, and fell steadily behind a single partition receiving ~1,000 events a second: Kafka lag passed 84,000 within a minute. It now takes a whole poll (up to 500 records) in one transaction and queues it with one Redis call.
2. **The scheduler.** It took 100 due events a second and delivered each with two transactions (a lock-and-claim, then a lock-and-record). It now takes batches of 100, claims each in one transaction, sends them concurrently on virtual threads (at most 32 HTTP calls in flight, so a batch isn't a burst of 100 connections at a merchant) and records every success in one `UPDATE`. A slow merchant endpoint delays only its own batch. The database cost of a delivery fell from ~0.6 ms to ~0.3 ms, and from two commits to a fraction of one.
3. **The HTTP client.** `HttpURLConnection` keeps five idle connections per host, so at several hundred deliveries a second almost every one opened a new TCP connection and the machine ran out of ephemeral ports (`Address already in use`), which failed deliveries and, being the same machine, the load generator's own requests. Webhooks are now sent with the JDK `HttpClient`, which keeps connections alive and shares them. It also does not follow redirects, which closes a way past the delivery-time check of the target URL; a `3xx` is a failed attempt.

How it is measured and watched: [running a load test](running.md#7-webhook-delivery-sla) and the dashboard's **Webhook SLA** row.

## The database work per payment

From `pg_stat_statements` over a run with webhooks, divided by the payments the run made (70,880 before, 91,506 after). The [query review](query-review.md) has the cause of each change.

| Statement | Calls per payment, before → after | Database time per payment, before → after |
|---|---|---|
| `update payment` | 3.0 → 2.0 | 1.07 ms → 0.47 ms |
| `update outbox_event` (a row's `UPDATE` right after its `INSERT`) | 3.2 → 0 | 0.51 ms → 0 |
| `update order_record` | 2.0 → 1.9 | 0.58 ms → 0.39 ms |
| `insert into payment_transition_log` | 3.8 → 3.8 | 1.00 ms → 0.88 ms |
| `insert into payment`, `order_record`, `outbox_event` | 1, 1, 3.2 → 1, 1, 3.0 | 1.12 ms → 0.91 ms |
| The order-expiry sweeper | a full scan of the order table a minute, 115 ms each → an index lookup, 0.05 ms | 0.10 ms → 0 |
| **All statements on the payment database** | | **≈ 5.2 ms → 3.3 ms** (the "before" is the 25 most expensive statements, so slightly low) |

## Where more throughput would come from

Nothing in the design stops the services scaling out — every scheduled job is ShedLock-guarded and state changes are row-locked — so the path is capacity, not a redesign:

1. **Run the load generator elsewhere**, and each service with several replicas on its own CPU budget, behind the gateway (itself replicated). On Kubernetes this is a replica count, and the request path was [measured scaling that way](../deployment/scaling.md#what-was-measured): 338, 758 and 999 req/s with 1, 2 and 3 replicas of the gateway and payment-service on a single-node kind cluster, 2.2× and 3.0×. Capture, which runs on one replica at a time, did not scale with them.
2. **Scale PostgreSQL** — faster storage or a managed instance for payment-service's database first, since it takes the most writes, and a connection pooler (PgBouncer) once replica count × pool size approaches `max_connections`.
3. **Partition the outbox work** — more Kafka partitions and a poller per partition (or change-data-capture with Debezium) so publishing scales with writes.
4. **Re-measure per step** with this test and the dashboard, raising `--threads` until p99 or errors move, to find the next bottleneck rather than guess it.

## Idempotency under concurrent retries

`idempotency_replay_test.py` sent each request 25 times under one `X-Idempotency-Key` — 20 at the same instant, 5 after the first had finished — through the gateway, with one instance of each service running:

| Request | Keys | Requests | Duplicates | Rows created | Duplicates that took effect |
|---|---|---|---|---|---|
| `POST /v1/payments` (UPI, net banking, card) | 1,000 | 25,000 | 24,000 | 1,000 payments | **0** |
| `POST /v1/orders` | 400 | 10,000 | 9,600 | 400 orders | **0** |

In all, **33,600 duplicate requests (24,000 + 9,600), none of which took effect.** A duplicate that arrived while the first request was in flight got `409 IDEMPOTENCY_CONFLICT`; one that arrived afterwards got the first `201` replayed with the same id. The database agreed: 1,000 payments for 1,000 orders, none with a second payment. Two runs gave the same result.

The control, measured before the fix, shows why it mattered: the same burst of 5 payment requests **without** a key created 5 payments on every one of 20 orders, and 2 to 5 of them were captured per order. An order now accepts one payment at a time ([an order takes one payment at a time](../api/behavior.md#an-order-takes-one-payment-at-a-time)), and the script fails the run unless each control order ends with exactly one payment.

## Reproducing these numbers

```bash
python provision_keys.py --merchants 50
python run_load_test.py --threads 100 --rampup 20 --duration 180
python idempotency_replay_test.py --payments 1000 --order-keys 400 --merchants 20
```

Absolute numbers depend on the hardware; the relative effect of each fix, and what the dashboard shows at each step, should reproduce.
