# Running a Load Test

## 1. Prerequisites

- The system running — locally ([setup](../local-development/setup.md)) or on kind ([running on kind](../deployment/running-on-kind.md)) — and ideally the [observability stack](../observability/dashboards.md), to watch the run live.
- [Apache JMeter 5.6](https://jmeter.apache.org/download_jmeter.cgi), unzipped anywhere. Point `JMETER_HOME` at it, or put its `bin/` on `PATH`.
- **Java 17 or 21 for JMeter.** JMeter 5.6 bundles Groovy 3, which can't load classes built for Java 25, so on a machine whose default `java` is 25 the plan's scripts fail with `Unsupported class file major version 69`. Point `JMETER_JAVA` at an older `java` executable; the services themselves stay on 25.
- Python 3.10+ for the two scripts (standard library only).

## 2. Run the services with load-test settings

Two settings would otherwise dominate the result:

| Variable | Set to | Why |
|---|---|---|
| `API_KEY_RATE_LIMIT_PER_MINUTE` (gateway) | e.g. `10000000` | The default is 200 requests per minute per key — 50 keys cap the whole test at ~170 req/s of `429`s |
| `PUBLIC_AUTH_RATE_LIMIT_PER_MINUTE` (gateway) | e.g. `100000`, when provisioning more than ~50 merchants | `provision_keys.py` signs up and logs in each merchant from one address: 2 requests per merchant against a default of 120 a minute per address, after which it gets `429`s |
| `JWT_RATE_LIMIT_PER_MINUTE` (gateway) | e.g. `10000000`, if the test uses dashboard (JWT) logins instead of API keys | The default is 600 requests per minute per merchant |
| `TRACING_SAMPLING_PROBABILITY` (every service) | e.g. `0.1` | Tracing every request means reporting every span; sample instead |

Set them in the environment of the services you start (locally) or in the ConfigMap (on kind), and restart.

## 3. Create API keys

```bash
cd microservices/load-test
python provision_keys.py --base-url http://localhost:8080 --merchants 50
```

Every run can reuse the same `keys.csv`. More keys spread traffic across more merchants; with the rate limit raised, 50 is plenty. Signing up is slow on purpose (bcrypt, three times per merchant), about 6 seconds each.

To also measure the [webhook SLA](#7-webhook-delivery-sla), give every merchant a webhook, written to a separate keys file:

```bash
python provision_keys.py --merchants 50 --out keys-webhooks.csv \
    --webhook-url http://localhost:8084/webhook/success --webhook-events PAYMENT_STATUS_CHANGED
python run_load_test.py --threads 100 --rampup 20 --duration 180 --keys keys-webhooks.csv
```

`/webhook/success` is operations-service's stand-in merchant endpoint, which answers `204` to anything (on Kubernetes, `http://operations-service/webhook/success`). `--webhook-events` takes a comma-separated list or `ALL`; `PAYMENT_STATUS_CHANGED` is the event a merchant acts on, one per payment. `ALL` adds an order and a payment-created event to each, three per payment.

## 4. Run it

```bash
python run_load_test.py --threads 100 --rampup 20 --duration 180
```

| Option | Default | Meaning |
|---|---|---|
| `--threads` | `50` | Concurrent virtual users |
| `--rampup` | `30` | Seconds over which they start |
| `--duration` | `120` | Total test length in seconds, ramp-up included |
| `--host`, `--port`, `--protocol` | `localhost`, `8080`, `http` | Where the gateway is |
| `--keys` | `keys.csv` | The API-key file |
| `--prometheus` | `http://localhost:9090` | The Prometheus that scrapes payment-service and operations-service, for the capture and webhook figures below. Skipped, with a note, if it isn't reachable |

Warm the system up first — one order through the gateway right after a restart can arrive before Eureka has propagated every instance and fail.

## 5. Read the result

The script prints one row per request type, the throughput, and the verdict for each target:

```
request                   count      rps     p50     p95     p99     max   errors
Create order             114521    639.6      46      65      83     216    0.00%
Initiate payment         114491    640.3     101     137     160     276    0.00%
Tokenize card               100      5.4      18      28      31      34    0.00%
ALL                      229112   1279.4      63     128     151     276    0.00%

throughput: 1279.4 successful req/s over the whole run

targets:
  PASS  p99_latency   actual 160.0  (target < 1000 ms (every request type))
  PASS  availability  actual 1.0  (target >= 99.99%)
```

After the request table and the targets, the script also reports what the load did *behind* the API, read from Prometheus while the test runs and afterwards until the work has drained (so a run takes a little longer than `--duration`):

```
capture (payments started and answered by the bank simulator):
  88560 payments started; 96.9% had been resolved when the run ended (backlog 2763, peak 3588); cleared 16.7 s after the run; 411.4 captures/s over the run

webhooks (delivery SLA: 99% within 30 s of the change):
  PASS  99.85% within 30 s of 91644 events (91644 delivered, 0 dead); p50 <= 1.0 s, p99 <= 2.0 s; peak 610 waiting, oldest unattempted 0.3 s, 0 left at the end; Kafka consumer lag peaked at 0 (0 at the end)
```

- **Capture** is the share of the run's payments the (simulated) bank had answered, and captured, by the moment the load stopped. A payment is *started* by an `AUTHORIZE_ATTEMPT` transition and *answered* by `AUTHORIZE_SUCCESS` (it is then captured at once) or `AUTHORIZE_FAIL`, so the difference is the backlog `BankCallbackSimulator` still has to work through. The simulator's own delay is 1 to 10 seconds by method, so a few thousand payments are always in flight; what matters is that the backlog doesn't grow and clears within seconds of the end.
- **Webhooks** needs merchants with a webhook ([step 3](#3-create-api-keys)); it is left out when there are none. It counts every delivery accepted during the run and the wait afterwards, by how long after the change it was, and passes at 99% within 30 seconds. `oldest unattempted` is how long the oldest event still waiting for its first attempt had been due, and the consumer lag is the events still in Kafka: both show a pipeline falling behind before the delivery times do.

Prometheus scrapes every 5 seconds, so these figures are good to about that.

Each run writes `results/<timestamp>/` (gitignored):

| File | Contents |
|---|---|
| `results.jtl` | Every sample, as CSV |
| `report/index.html` | JMeter's HTML report — response times over time, percentiles, throughput, errors by type |
| `summary.json` | The numbers above, including the `capture` and `webhooks` sections |
| `jmeter.log` | JMeter's own log — look here if the run records nothing |

`python run_load_test.py --summary-only results/<timestamp>` grades an earlier run again.

While it runs, the Grafana dashboard shows the same test from the servers' side: throughput and p99 at the gateway and per service, database pool use, and whether the outbox is keeping up. When the client's numbers and the gateway's disagree, the difference is time spent outside the services — in the load generator or the network.

## 6. Replay test for idempotency

A separate script checks the [idempotency guarantee](../api/idempotency-and-rate-limits.md#idempotency) rather than speed. For each idempotency key it sends 20 identical requests at the same instant and 5 more after the first has finished, for payments (UPI, net banking and card) and for order creation, then repeats a payment burst with no key as a control:

```bash
python idempotency_replay_test.py --payments 1000 --order-keys 400 --merchants 20
```

It needs the same `keys.csv` and raised rate limit as the load test. It prints a JSON summary and a `PASS`/`FAIL` line, and exits `1` if any key produced more than one order or payment. Every order it creates carries the run id (`receipt` starting `idem-<run>-`, and `notes.idemRun`), so the count can be confirmed in `payflo_payment`:

```sql
select count(distinct o.id) as orders, count(p.id) as payments
from order_record o left join payment p on p.order_id = o.id
where o.receipt like 'idem-<run>-pay-%';
```

## 7. Webhook delivery SLA

The requirement is that [99% of webhooks are delivered within 30 seconds of the change, and all within 24 hours](../requirements.md#non-functional). Run the load test with webhook merchants ([step 3](#3-create-api-keys)) and the `webhooks` section of the output is the measurement: the share of events the merchant's endpoint accepted within 30 seconds of the change that caused them, over every delivery made during the run and the wait after it.

In Grafana the same number is live, on the dashboard's **Webhook SLA** row (the share delivered within 30 s over the time range, latency p50/p99 against the 30 s line, and the backlog with the age of the oldest event still waiting). It comes from the `payflo_webhook_delivery_latency_seconds` histogram; see [metrics](../observability/metrics.md).

## 8. Find the slow queries

`pg_stat_statements` shows which statements the load makes the database spend its time on, and `EXPLAIN` why; the steps, and what the last review found, are in the [query and index review](query-review.md).