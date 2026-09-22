# Load Testing

How to measure PayFlo against its [non-functional targets](../requirements.md#non-functional) — throughput, p99 latency and availability — and read the result.

Everything lives in `microservices/load-test/`:

| File | What it does |
|---|---|
| `payflo-load-test.jmx` | The Apache JMeter plan: each virtual user is one merchant's backend creating orders and paying them through the gateway |
| `provision_keys.py` | Signs up N test merchants through the gateway and writes their API keys to `keys.csv` (gitignored) |
| `run_load_test.py` | Runs the plan headless, writes JMeter's HTML report, and grades the run against the three targets |

## What one virtual user does

1. Takes one merchant's API key from `keys.csv` for the whole run (thread *n* gets key *n* mod the number of keys), so traffic is spread across merchants and a card tokenized by that merchant is always charged by the same merchant.
2. **Creates an order** (`POST /v1/orders`) with a random amount and a customer, which calls merchant-service to resolve the customer.
3. Once per thread, **tokenizes a test card** (`POST /v1/vault/tokenize`).
4. **Initiates a payment** for the order (`POST /v1/payments`) with a fresh `X-Idempotency-Key` — 40% UPI, 30% netbanking, 30% card. A card payment goes gateway → payment-service → vault-service.
5. Repeats until the test's duration is up.

A request passes only with `200` or `201`. A payment the mock bank declines is still a `201` — it's a correct answer, not an error ([why](../known-gaps/api-behavior.md#a-failed-payment-is-still-a-201)) — and the plan's values never trigger a decline, so the recorded errors are real failures.

## How each target is measured

| Target | Measured as | Where |
|---|---|---|
| **Throughput ≥ 10,000 TPS** | Successful requests per second over the whole run (ramp-up included) | `run_load_test.py`, and live on the dashboard's gateway throughput panel |
| **p99 < 1 s** | The 99th-percentile response time of **every** request type, as the client saw it | `run_load_test.py` (per request type, from JMeter's samples), and the dashboard's gateway p99 |
| **Availability ≥ 99.99%** | Share of requests that succeeded during the run | `run_load_test.py` and the dashboard's availability panel |

Availability in the requirements means uptime over months — about 52 minutes of downtime a year — and a load test can't measure that. What a load test *can* show is that the system keeps answering correctly under load, which is what the success rate here measures. Measuring the real target needs the service running continuously with the same metric recorded over time.

## Pages

- [Running a load test](running.md) — setup, the commands, and reading the output.
- [Results](results.md) — the measured numbers, the six problems the test found, and what 10,000 TPS would take.
