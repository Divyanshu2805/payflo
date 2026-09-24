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
| `TRACING_SAMPLING_PROBABILITY` (every service) | e.g. `0.1` | Tracing every request means reporting every span; sample instead |

Set them in the environment of the services you start (locally) or in the ConfigMap (on kind), and restart.

## 3. Create API keys

```bash
cd microservices/load-test
python provision_keys.py --base-url http://localhost:8080 --merchants 50
```

Every run can reuse the same `keys.csv`. More keys spread traffic across more merchants; with the rate limit raised, 50 is plenty.

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

Warm the system up first — one order through the gateway right after a restart can arrive before Eureka has propagated every instance and fail.

## 5. Read the result

The script prints one row per request type and the verdict for each target:

```
request                   count      rps     p50     p95     p99     max   errors
Create order             114521    639.6      46      65      83     216    0.00%
Initiate payment         114491    640.3     101     137     160     276    0.00%
Tokenize card               100      5.4      18      28      31      34    0.00%
ALL                      229112   1279.4      63     128     151     276    0.00%

targets:
  MISS  throughput    actual 1279.4  (target >= 10000 req/s)
  PASS  p99_latency   actual 160.0  (target < 1000 ms (every request type))
  PASS  availability  actual 1.0  (target >= 99.99%)
```

Each run writes `results/<timestamp>/` (gitignored):

| File | Contents |
|---|---|
| `results.jtl` | Every sample, as CSV |
| `report/index.html` | JMeter's HTML report — response times over time, percentiles, throughput, errors by type |
| `summary.json` | The numbers above |
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
