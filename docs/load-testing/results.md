# Results

Measured on a single development laptop (32 logical cores, 16 GB for Docker), with everything on the same machine: JMeter, the seven services started with `java -jar`, PostgreSQL, Redis and Kafka in Docker, and the observability stack. Each run is 100 virtual users against the gateway, following [running a load test](running.md), with the rate limit raised and tracing sampled at 10%.

## Where it stands

| Target | Required | Measured | |
|---|---|---|---|
| Throughput | 10,000 TPS | **837 req/s** sustained for 3 minutes (≈ 419 orders + 419 payments per second) | Not met |
| p99 latency | < 1 s | **247 ms** for the slowest request type (payments); 232 ms across all requests; max 398 ms | Met |
| Availability | 99.99% | **100%** — 0 failed requests out of 149,582 | Met for the run |

Latency and correctness hold with margin. Throughput is roughly a twelfth of the target, and on this setup it's capped by the machine rather than by any one service: total CPU reached 100% while no service's own CPU exceeded ~16%, because the load generator, seven JVMs, PostgreSQL, Kafka and Redis all compete for the same cores. The availability figure is for the run only — the real target is uptime over months (see [how each target is measured](README.md#how-each-target-is-measured)).

## How it got there

The first runs found six problems, each visible only under concurrency. Every row is the same test after the fixes above it:

| Run | Change | Throughput | p99 (worst type) | Errors | What the metrics showed |
|---|---|---|---|---|---|
| 1 | Baseline | 250 req/s | 502 ms | 0% | payment-service answered `/v1/orders` in ~25 ms but the gateway took ~370 ms — requests were queueing inside the gateway |
| 2 | Gateway proxy pool raised from 5 to 200 connections per route | 84 req/s | 5.7 s | 50% | With the gateway no longer throttling, 100 concurrent requests reached payment-service: its 10-connection database pool ran out, requests waited 30 s, and the resulting failures opened the merchant-service circuit breaker |
| 3 | Pools sized (payment 40), 5 s acquisition timeout, open breaker answered as `503` | 158 req/s | 5.7 s | 54% | Still out of connections — PostgreSQL showed the pool's connections **idle**, held by the application but not querying |
| 4 | Open-Session-In-View turned off | 717 req/s | 647 ms | ~0% | Connections now held only inside transactions. The outbox backlog peaked at ~89,000 events and drained at ~10/s |
| 5 | Outbox published in acknowledged batches (and the simulator bounded) | 745 req/s | 611 ms | 1.2% | Backlog drained ~66× faster. One burst of 503s at the end of ramp-up: a DB-pool timeout had counted as a merchant-service failure and opened its breaker |
| 6 | Breakers and retries count only remote failures; wider breaker window | **837 req/s** | **247 ms** | **0%** | — |

Details of each cause and fix are in [known pitfalls](../practices/gotchas/README.md): the [gateway's proxy pool](../practices/gotchas/microservices.md#the-gateways-proxy-pool-allows-5-connections-per-route), [Open-Session-In-View](../practices/gotchas/spring-and-jpa.md#open-session-in-view-holds-a-connection-across-remote-calls), [pool sizing](../practices/gotchas/spring-and-jpa.md#size-the-connection-pool-and-fail-fast-when-its-exhausted); and in [service communication](../architecture/service-communication.md) for the outbox and the breaker scope.

## What's still limiting

- **The machine.** With the load generator on the same host, adding virtual users adds latency, not throughput. A real measurement puts JMeter on separate machines.
- **The outbox under sustained load.** Each order produces 3–4 events (order created, payment created, two status changes), so ~420 orders/s write ~1,500 events/s, while one poller publishes ~650/s. At this rate the backlog grows during a run and drains afterwards — webhooks fall behind real time, though none are lost. `payflo_outbox_pending` on the dashboard shows it.
- **payment-service's commit rate.** Its pool still peaks at 40 connections in use under the heaviest load; each payment is three transactions (record, apply result, and the simulator's authorize-and-capture), and each commit waits on PostgreSQL's WAL flush.

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
