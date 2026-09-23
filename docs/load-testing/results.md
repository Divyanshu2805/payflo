# Results

Measured on a single development laptop (32 logical cores, 16 GB for Docker), with everything on the same machine: JMeter, the seven services started with `java -jar`, PostgreSQL, Redis and Kafka in Docker, and the observability stack. Each run is 100 virtual users against the gateway, following [running a load test](running.md), with the rate limit raised and tracing sampled at 10%.

## Where it stands

| Target | Required | Measured | |
|---|---|---|---|
| Throughput | 10,000 TPS | **~1,000 req/s** for 3 minutes (≈ 500 orders + 500 payments per second) with payments being captured alongside; three runs gave 963, 1,005 and 1,023 | Not met |
| p99 latency | < 1 s | **219–267 ms** for the slowest request type (payments) across those runs | Met |
| Availability | 99.99% | **100%** — 0 failed requests in each run (172,058 to 183,268 requests) | Met for the run |

Latency and correctness hold with margin. Throughput is roughly a tenth of the target, and on this setup it's capped by the machine rather than by any one service: total CPU sits at ~96% of 32 cores, shared between the Docker VM (PostgreSQL alone ~5 cores), JMeter, payment-service, Docker Desktop's network proxy (~4 cores) and the other JVMs.

**Two throughput figures, and why.** Earlier runs reached 1,279 req/s, but that was the rate at which requests were *accepted*: the bank simulator resolved only ~50 payments a second, so nearly every payment was still `AUTHORIZING` when the run ended and its capture happened afterwards. With the simulator resolving payments during the run, capture competes for the same database, and the figure is ~1,000 req/s. Even then captures don't fully keep pace — about 57% of a run's payments are captured before it ends, the backlog peaks near 40,000 and clears ~40 seconds after — so the rate this laptop can hold indefinitely is lower still. The availability figure is for the run only — the real target is uptime over months (see [how each target is measured](README.md#how-each-target-is-measured)).

## How it got there

The runs found a series of problems, each visible only under concurrency. Every row is the same test after the fixes above it. Runs 1–8 measure accepted requests with capture deferred; from run 9 capture runs alongside:

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
| 11 | Time-ordered ids (UUID v7) in payment-service | **1,005 req/s** | **267 ms** | **0%** | Within run-to-run variation of run 10 |

Details of each cause and fix are in [known pitfalls](../practices/gotchas/README.md): the [gateway's proxy pool](../practices/gotchas/microservices.md#the-gateways-proxy-pool-allows-5-connections-per-route), [Open-Session-In-View](../practices/gotchas/spring-and-jpa.md#open-session-in-view-holds-a-connection-across-remote-calls), [pool sizing](../practices/gotchas/spring-and-jpa.md#size-the-connection-pool-and-fail-fast-when-its-exhausted); and in [service communication](../architecture/service-communication.md) for the outbox and the breaker scope.

## What's still limiting

- **The machine.** With the load generator on the same host, adding virtual users adds latency, not throughput. A real measurement puts JMeter on separate machines.
- **Capture against the same database.** A payment is four commits — create the order, record the payment, apply the gateway result, authorize-and-capture — and capture alone is a locked read, three state changes, two updates and an event. With 16 captures at once (`payment.simulator.concurrency`) the simulator resolves ~820 payments a second on an idle system but only ~280 while requests are arriving, against ~500 arriving. Lowering it gives requests more of the database and lets captures fall further behind; it doesn't add capacity.
- **payment-service's database connections.** All 40 are in use for the whole run, with 65–76 requests waiting at the peak. Sampled in PostgreSQL, about half of them are idle inside a transaction — waiting for the application's next statement, which on a saturated machine, through Docker Desktop's port proxy, is slow to arrive — and most of the rest are waiting for the WAL flush.
- **The disk under Docker Desktop.** ~470 `fdatasync` calls a second (2.1 ms each), so commits are flushed in groups. Removing `commit_delay`, and turning off the pool's auto-commit round trips, each changed nothing measurable (1,023 and 947 req/s) and were not kept.
- **Webhooks and the outbox keep up.** The outbox backlog stays under ~1,500 events and the webhook consumer's Kafka lag under ~1,150 throughout a run. The load-test merchants have no webhook targets, so this covers looking targets up, not signing and storing deliveries.
- **The dataset.** These runs were made on top of ~1.5 million orders and payments left by earlier runs; a fresh database was not measured.

## What 10,000 TPS would take

Nothing in the design stops the services scaling out — every scheduled job is ShedLock-guarded and state changes are row-locked — so the path is capacity, not a redesign:

1. **Run the load generator elsewhere**, and each service with several replicas on its own CPU budget, behind the gateway (itself replicated). On Kubernetes this is a replica count; the local cluster is single-node.
2. **Scale PostgreSQL** — faster storage or a managed instance for payment-service's database first, since it takes the most writes, and a connection pooler (PgBouncer) once replica count × pool size approaches `max_connections`.
3. **Partition the outbox work** — more Kafka partitions and a poller per partition (or change-data-capture with Debezium) so publishing scales with writes.
4. **Re-measure per step** with this test and the dashboard, raising `--threads` until p99 or errors move, to find the next bottleneck rather than guess it.

## Reproducing these numbers

```bash
python provision_keys.py --merchants 50
python run_load_test.py --threads 100 --rampup 20 --duration 180
```

Absolute numbers depend on the hardware; the relative effect of each fix, and what the dashboard shows at each step, should reproduce.
