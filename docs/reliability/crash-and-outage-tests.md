# Crash and Outage Tests

What happens to a payment when something dies in the middle of it? These tests answer that by doing it: a service is killed with no warning, or PostgreSQL, Redis or Kafka is switched off, while customers are placing and paying orders, and then the system is checked for the three failures that matter in a payment system — a payment **lost**, **duplicated** or **stuck**.

**Result: all seven scenarios pass all eight checks.** In every one, every customer's order and payment exists exactly once, every payment reached an end state, the money adds up, and every captured payment's webhook reached the merchant.

## Running it

```bash
cd microservices
python chaos/crash_and_outage_test.py                         # every scenario, about 15 minutes
python chaos/crash_and_outage_test.py redis-outage            # one
python chaos/crash_and_outage_test.py --list
```

It needs the stack running as for the [hand checks](../practices/testing.md#what-is-verified-by-hand) (the gateway, the four services, and the PostgreSQL, Redis and Kafka containers), Docker and Python 3.10+, and nothing else: it uses only the standard library. It restarts the gateway first with the per-key rate limit raised, because customers retry hard during a fault and the default 200 requests a minute would mistake that for abuse. It writes a JSON report to `chaos/results/` (gitignored) and each service's log to `chaos/logs/`.

## What each scenario does

Each scenario creates six fresh merchants, each with an API key, a vaulted test card and a webhook pointing at a receiver the script runs itself. Thirty-six customers then start over the first twelve seconds. Each places an order and pays it, **retrying with an idempotency key** the way a careful client library would: no answer, a `5xx`, a `429` and an in-progress `409` are all retried, with the same key, for up to 150 seconds. Half the customers also send a receipt number, half rely on the key alone, so a duplicate order can't hide behind the receipt's unique index. Six seconds in, the fault lands. When the component is back, the system is given up to five minutes to settle, and then the checks run.

"Kill" is a hard kill (`taskkill /F` or `SIGKILL`): no shutdown hooks, no draining, the same as a crashed process or a pulled plug. "Outage" is `docker stop -t 0`, which is the same for a container.

| Scenario | The fault | Share of card payments |
|---|---|---|
| `payment-service-crash` | Kill payment-service for 6 s | 30% |
| `vault-service-crash` | Kill vault-service for 6 s | 100%, so the vault is on every payment's path |
| `gateway-crash` | Kill the gateway for 4 s | 30% |
| `operations-service-crash` | Kill operations-service for 8 s: webhook delivery stops while payments carry on | 30% |
| `kafka-outage` | Stop Kafka for 25 s | 30% |
| `redis-outage` | Stop Redis for 25 s: the idempotency claims, rate limits and caches go with it | 30% |
| `postgres-outage` | Stop PostgreSQL for 20 s | 30% |

## What is checked

| Check | Means |
|---|---|
| Every customer got through | The clients' retries were enough: nobody gave up |
| **No duplicates**: one order per customer | However often the client retried, from the database: one order for each customer's reference |
| **No duplicates**: one payment per order | One payment for each order, and as many as the clients were told about |
| **Nothing lost** | Every payment a client was told was created exists in the database |
| **Nothing stuck** | Every payment ends `CAPTURED` or `FAILED`; none is left `AUTHORIZING` or `CREATED` |
| **Money is consistent** | Every captured payment's order is `PAID`, and no order is `PAID` without a captured payment |
| **Outbox drained** | Every event written to the outbox was published to Kafka |
| **Events delivered** | Every captured payment's webhook reached the merchant's server, at least once |

## Results

| Scenario | Customers that had to retry | Payments `CAPTURED` / `FAILED` | Webhooks received |
|---|---|---|---|
| `payment-service-crash` | 26 of 36 | 36 / 0 | 108 |
| `vault-service-crash` | 0 | 8 / 28 | 72 |
| `gateway-crash` | 30 | 31 / 5 | 106 |
| `operations-service-crash` | 0 | 34 / 2 | 108 |
| `kafka-outage` | 0 | 32 / 4 | 108 |
| `redis-outage` | 32 | 35 / 1 | 108 |
| `postgres-outage` | 25 | 34 / 2 | 105 |

What the numbers show:

- **A `FAILED` payment is not a lost one.** The simulated bank declines a few payments by design (5–10%), which is most of the `FAILED` column. The vault scenario is the exception on purpose: with the vault down, 27 of the 28 failures are the saga **compensating** (`PAYMENT_GATEWAY_ROUTER_UNREACHABLE`: nothing was charged, the order stays payable) and one is a bank decline. No customer had to retry, because a clean `FAILED` is an answer, not an error.
- **Crashes are absorbed by retries plus idempotency.** When payment-service, the gateway, Redis or PostgreSQL was down, 25–32 of 36 customers retried, and the keys meant none of those retries created a second order or payment.
- **The outages that don't touch the request path are invisible to customers.** Killing operations-service or stopping Kafka delayed webhooks and left the outbox to catch up afterwards (0 customers retried), and every event still arrived.
- **Webhooks are at least once.** The receiver saw more webhooks than payments because each payment sends several events (created, authorized, captured); the check is that none is *missing*, not that none is repeated, and each carries a stable event id so a receiver can drop a duplicate.

## What this found

The first full run passed every check, but reading the design against it showed one place the "no duplicates" claim rested on Redis alone: **an order created with an idempotency key kept the key only in Redis**. A payment or a refund has always had a unique `(merchant_id, idempotency_key)` index in the database behind it; an order didn't, so a retry whose first response was lost *while Redis was down* could create a second order. The scenario didn't happen to hit that narrow window, but the gap was real, so it was closed: Flyway migration `V3__order_idempotency_key.sql` adds the column and the unique index, a replay returns the original order, a changed request with the same key is `422`, and two simultaneous requests with one key resolve to one order. `PaymentFlowIntegrationTest` forgets the Redis key and retries, and races ten requests with one key against real PostgreSQL; both end with exactly one order.

## Settlement interruptions

The harness doesn't kill a process in the middle of a settlement, because the nightly run is on a 23:00 timer with no way to start it on demand. The same crash points are covered instead by `SettlementIntegrationTest` (in operations-service), on a real database with the real recovery job, by stopping the process's work at each step:

| Interrupted | What the test does | Outcome |
|---|---|---|
| Right after the payout was recorded, before the transfer reached the bank | Leaves an `INITIATED` settlement behind, as a crash would | The recovery job restarts the transfer; the bank identifies it by the settlement id, so it is paid once |
| After the bank paid, before payment-service was told | Makes `markSettled` fail | The settlement stays `PROCESSED` and its payments are held back from a second payout; the recovery job marks them once payment-service is back |
| The bank never answers | Puts the simulated bank in `TIMEOUT` | The recovery job fails the transfer after the timeout and the payments are paid out by the next run |
| merchant-service down | Makes the bank-details lookup fail | The merchant is skipped and nothing is written |

A live kill of operations-service during a settlement would be a natural addition once there is an endpoint to start a settlement on demand.

## What it doesn't cover

- **One instance of everything.** No scenario takes down one of several replicas, because the stack runs a single instance of each service and a single PostgreSQL. A multi-replica run is part of the scaling work.
- **Load is small.** Thirty-six customers across six merchants is enough to put many requests in flight at the moment of the fault; it is a correctness test, not a throughput one. The [load test](../load-testing/README.md) measures speed.
- **Faults are clean.** A process killed or a container stopped, not a slow disk, a network partition, a half-open connection or a clock jump.
- **The bank is simulated**, so "a payment is never lost" is shown for PayFlo's side of the bank call only. A real acquirer adds cases (a charge that succeeded where the answer was lost) that the [timeout sweeper](../architecture/flows/payment.md) and reconciliation would have to handle.

## Adding a scenario

A scenario is a name, a fault function and the share of card payments in `SCENARIOS` at the bottom of `crash_and_outage_test.py`. A fault is any function that breaks something and then heals it: `crash("payment-service")` and `outage(KAFKA_CONTAINER, 25)` are two. The checks run unchanged after it.
