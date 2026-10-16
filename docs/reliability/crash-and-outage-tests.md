# Crash and Outage Tests

What happens to a payment when something dies in the middle of it? These tests answer that by doing it: a service is killed with no warning, or PostgreSQL, Redis or Kafka is switched off, while customers are placing and paying orders, and then the system is checked for the three failures that matter in a payment system — a payment **lost**, **duplicated** or **stuck**.

**Result: all seven scenarios pass all eight checks.** In every one, every customer's order and payment exists exactly once, every payment reached an end state, the money adds up, and every captured payment's webhook reached the merchant. Each run also times the recovery: once the component is back, a new payment goes through within **13 seconds** in the worst case, and every payment has settled and the outbox drained within **22 seconds**.

## Running it

```bash
cd microservices
python chaos/crash_and_outage_test.py                         # every scenario, about 12 minutes
python chaos/crash_and_outage_test.py redis-outage            # one
python chaos/crash_and_outage_test.py --port-offset 100       # a stack started with demo.py --port-offset 100
python chaos/crash_and_outage_test.py --list
```

It needs the stack running as for the [hand checks](../practices/testing.md#what-is-verified-by-hand) (the gateway, the four services, and the PostgreSQL, Redis and Kafka containers), Docker and Python 3.10+, and nothing else: it uses only the standard library. It restarts the gateway first with the per-key rate limit raised, because customers retry hard during a fault and the default 200 requests a minute would mistake that for abuse. It prints the checks and a recovery table, writes a JSON report to `chaos/results/` (gitignored), with each scenario's checks, counts and recovery times, and writes each service's log to `chaos/logs/`.

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

## How recovery is timed

The clock starts when the broken component is **back**: a restarted service reports healthy, or a stopped container has been started again. From then on it measures two things:

| Measure | Means |
|---|---|
| **First payment** | Seconds until a new customer's payment goes through again. A new order is paid by UPI every second, by a seventh merchant that no check reads, until one is seen `CAPTURED` |
| **All settled** | Seconds until every customer is done, every one of their payments has ended `CAPTURED` or `FAILED`, and none of their events is still waiting in the outbox. The database is read every second |

A payment takes a few seconds even with nothing broken, because the simulated bank answers a UPI payment in 1 to 3 seconds. So each scenario first times three payments before the fault, and reports the middle one as the **normal** time to read the first figure against. "All settled" can be shorter than "first payment": when every customer had already finished during the fault, there is nothing left to settle.

## Results

Measured on 2026-10-16. Recovery times are in seconds from the component being back; "normal" is a payment with nothing broken.

| Scenario | Customers that had to retry | Payments `CAPTURED` / `FAILED` | Webhooks received | Recovery: first payment (normal) | Recovery: all settled |
|---|---|---|---|---|---|
| `payment-service-crash` | 14 of 36 | 30 / 6 | 108 | 4.1 s (2.9 s) | 6.9 s |
| `vault-service-crash` | 0 | 21 / 15 | 94 | 1.9 s (3.0 s) | 0.6 s |
| `gateway-crash` | 20 | 34 / 2 | 108 | 3.3 s (3.0 s) | 6.8 s |
| `operations-service-crash` | 0 | 35 / 1 | 108 | 3.0 s (3.0 s) | 0.6 s |
| `kafka-outage` | 0 | 33 / 3 | 108 | 2.4 s (4.0 s) | 21.3 s |
| `redis-outage` | 21 | 32 / 4 | 108 | 10.2 s (3.0 s) | 16.9 s |
| `postgres-outage` | 16 | 36 / 0 | 108 | 12.8 s (2.1 s) | 20.1 s |

What the numbers show:

- **A `FAILED` payment is not a lost one.** The simulated bank declines a few payments by design (5–10%), which is most of the `FAILED` column. The vault scenario is the exception on purpose: with the vault down, 14 of the 15 failures are the saga **compensating** (`PAYMENT_GATEWAY_ROUTER_UNREACHABLE`: nothing was charged, the order stays payable) and one is a bank decline. No customer had to retry, because a clean `FAILED` is an answer, not an error.
- **Crashes are absorbed by retries plus idempotency.** When payment-service, the gateway, Redis or PostgreSQL was down, 14–21 of 36 customers retried, and the keys meant none of those retries created a second order or payment.
- **The outages that don't touch the request path are invisible to customers.** Killing operations-service or stopping Kafka delayed webhooks and left the outbox to catch up afterwards (0 customers retried), and every event still arrived.
- **A crashed service recovers in about the time of a normal payment; a data store takes longer.** Once a crashed service is healthy, a new payment is captured in about the normal time, a second or so more after payment-service. A data store takes longer: after Redis or PostgreSQL is started, the services' connections have to notice it is back and reconnect, so the first payment comes 10 to 13 seconds later, against 2 to 3 seconds normally. Kafka doesn't stop payments at all (the outbox holds the events), but the outbox takes 21 seconds to drain once the producers reconnect.
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

## Adding a scenario

A scenario is a name, a fault function and the share of card payments in `SCENARIOS` at the bottom of `crash_and_outage_test.py`. A fault is any function that breaks something and then heals it: `crash("payment-service")` and `outage(KAFKA_CONTAINER, 25)` are two. The checks run unchanged after it.
