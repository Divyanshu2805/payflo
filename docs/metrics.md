# Project Metrics

Measured figures for PayFlo, each with how to re-check it. Nothing here is an estimate unless it says "about". The performance, scaling and reliability figures are from runs made on 2026-10-07; the test and code counts were taken on 2026-10-11.

Everything was measured on one development laptop (32 logical cores, 16 GB for Docker) that ran the load generator, PostgreSQL, Redis, Kafka and all seven services together.

## Performance

100 virtual users for 3 minutes through the gateway, each creating an order and paying it by UPI, net banking or card ([how](load-testing/README.md)).

| Measure | Target | Measured |
|---|---|---|
| Throughput | As much as one machine gives | **1,272 requests a second** (about 636 orders and 636 payments a second), with payments authorized and captured alongside |
| p99 latency | Under 1 second | **171 ms** for the slowest request type |
| Errors under load | None | **0 failed requests** in 227,613 |
| Payments captured during the run | — | **96.3%** by the time the load stops, 505 captures a second; the rest are inside the simulated bank's 1 to 10 second delay |
| Webhook delivery | 99% within 30 seconds of the change | **99.85%** of 91,644 events, p99 under 2 seconds |
| Webhook delivery, every event subscribed | 99% within 30 seconds | **100.0%** of 283,486 events, about 1,575 deliveries a second |
| Outbox | Keeps pace with writes | About 1,500 events a second published, under 1,500 pending at any time |
| Database time per payment | — | **3.3 ms**, down from 5.2 ms ([query review](load-testing/query-review.md)) |

The first run of the same test managed 250 requests a second at a p99 of 502 ms. The five-fold gain came from 19 fixes, each found by running the test and reading the dashboard:

| Fix | Requests a second after it |
|---|---|
| Baseline: requests queued in the gateway's 5-connection proxy pool | 250 |
| Open-Session-In-View turned off, so a connection is held only inside a transaction | 717 |
| Circuit breakers and retries count only remote failures | 837 |
| Order creation no longer holds a connection across the customer lookup; PostgreSQL group commit | 1,279 |
| Capture moved inside the run instead of after it | 660 |
| PostgreSQL given 2 GB of cache and a larger WAL budget | 963 |
| Writes the database never needed removed; bank answers and the webhook pipeline batched | **1,272** |

The full run-by-run table, with what the metrics showed at each step, is in the [load-test results](load-testing/results.md).

## Scaling

On a single-node kind cluster, 200 virtual users for 3 minutes, each replica limited to one CPU ([scaling](deployment/scaling.md)).

| Replicas of the gateway and payment-service | Requests a second | Against one replica | Errors |
|---|---|---|---|
| 1 and 1 | 338 | 1.0× | 0 |
| 2 and 2 | 758 | 2.2× | 0 |
| 3 and 3 | **999** | **3.0×** | 0 |

| Measure | Measured |
|---|---|
| Rolling restart of both services under load | 19 failed requests in 226,834 (**99.992%** succeeded), 1,133 requests a second throughout |
| Database after all the cluster's runs (408,000 payments) | No order with two live payments, no idempotency key used twice, no payment missing its transition log, none stuck `AUTHORIZING`, outbox empty |

## Reliability

| Measure | Measured | How to check |
|---|---|---|
| Crash and outage scenarios | **7 of 7 pass all 8 checks**: kill payment-service, vault-service, the gateway or operations-service, or stop Kafka, Redis or PostgreSQL, while 36 customers pay | `python chaos/crash_and_outage_test.py` |
| Payments lost, duplicated or stuck across the 7 scenarios | 0 | same |
| Webhooks for captured payments that reached the merchant | All of them, in every scenario | same |
| Duplicate requests under one idempotency key | **33,600 sent, 0 took effect** (24,000 payment and 9,600 order requests, 20 at the same instant per key) | `python idempotency_replay_test.py` |
| Settlement interrupted after any of its steps | Finished by the recovery job, never paid twice | `SettlementIntegrationTest` |

See the [crash and outage tests](reliability/crash-and-outage-tests.md).

## Quality

| Metric | Value | How to check |
|---|---|---|
| Automated tests | **633**, all passing | `./mvnw verify` in `microservices/` |
| By module | common-lib 111, merchant-service 139, payment-service 167, operations-service 144, vault-service 23, api-gateway-service 47, discovery-service 1, config-service 1 | same |
| Test classes | 80, about 10,800 lines | `src/test` in each module |
| Integration tests | Each service started as the real application on real PostgreSQL, Redis and Kafka in containers; every migration run on an empty database and every entity validated against it | [testing](practices/testing.md) |
| Time for the whole suite | About 5 and a half minutes | `./mvnw verify` |
| CI checks per push | 5: the OpenAPI spec against the controllers, the build with every test, a dependency vulnerability scan, a secret scan, a configuration report | `.github/workflows/ci.yml` |
| Public endpoints in the OpenAPI spec | 53 of 53 controller mappings | `python docs/api/check_openapi.py` |
| Fixable HIGH or CRITICAL dependency vulnerabilities | 0 (the first scan found twelve vulnerable packages) | the `dependency-scan` job |

## Security

| Parameter | Value | Where |
|---|---|---|
| Places a request is authenticated | 1: the gateway | `GatewayAuthFilter` |
| Password and API-key secret hashing | bcrypt | merchant-service |
| Access token lifetime | 100 minutes, revocable | `JwtUtil`, `SessionService` |
| Refresh token | 7 days, single use | same |
| Login lockout | 10 wrong passwords in 15 minutes | `LoginAttemptTracker` |
| API-key rotation grace period | 0 to 24 hours | `ApiKeyServiceImpl` |
| Card encryption | AES-256-GCM with a data key per card, wrapped by a master key only vault-service holds | `VaultEncryptionConfig` |
| CVV | Never stored | `vault_card` has no column for it |
| Services that can decrypt a card | 1 of 7 | vault-service |
| Webhook signature | HMAC-SHA256 over `<timestamp>.<body>`, per attempt, with a secret per endpoint stored AES-encrypted | `WebhookSignatures` |
| Audit log | 16 actions, written in the transaction that makes the change; `UPDATE`, `DELETE` and `TRUNCATE` refused by a database trigger | `AuditLogService`, `audit_log_append_only` |
| Request body limit | 1 MB, refused before authentication | `RequestSizeLimitFilter` |
| Ports published beyond loopback | 0 locally; only the gateway on Kubernetes | `server.address`, the manifests |

### Rate and velocity limits

| Rule | Limit |
|---|---|
| Requests per API key | 200 a minute |
| Requests per merchant, dashboard traffic | 600 a minute |
| Signup and login per client address | 120 a minute |
| Failed authentications per client address | 30 a minute |
| Cards tokenized per merchant | 30 a minute and 600 an hour |
| Card testing | 20 failed card payments in 10 minutes that are at least half of the merchant's card payments |
| Card payments per order | 5 |

## Platform defaults

| Setting | Value |
|---|---|
| Order amount | 1 paisa to INR 5,000,000, INR only |
| Order expiry | 15 minutes |
| Payment left unanswered by the bank | Failed after 15 minutes |
| Authorization left uncaptured | Lapses after 60 minutes |
| Idempotency replay window | 24 hours; orders, payments and refunds also keep the key in the database |
| Webhook attempts | 7: at once, then after 1 min, 5 min, 30 min, 2 h, 8 h and 24 h |
| Settlement | Nightly at 23:00; fee 2%, GST 18% of the fee |
| Unanswered payout transfer | Failed after 120 minutes and retried by the next run |
| Outbox | Polled every second in batches of 500; published events kept 7 days |
| Simulated bank | Card 2 to 6 s and 90% approved; UPI and wallet 1 to 3 s and 95%; net banking 4 to 10 s and 80% |
| Page size | 20 by default, 100 at most |
| Analytics report range | 366 days at most |
| Database connection pools | payment 40, merchant 20, operations 16, vault 10; 5 seconds to wait for one |

## Scale of the codebase

| Metric | Value |
|---|---|
| Services | 7: the gateway, four business services, discovery and configuration |
| Maven modules | 9 (the seven services, `common-lib` and `test-support`) |
| Application code (Java, without tests) | 315 files, about 14,500 lines |
| Test code | 80 files, about 10,800 lines |
| Databases / tables / migrations | 4 / 18 / 12 |
| Public endpoints / internal endpoints | 53 / 11 |
| Kafka topics / event types | 4 / 11 |
| Scheduled jobs, each under a distributed lock | 11 |
| Payment statuses / state-machine events | 11 / 12 ([enums](schema/enums.md)) |
| Architecture decision records | 8 |
| Dashboard | about 2,800 lines of HTML, CSS and JavaScript, no build step and no dependencies |
| Sample shop | about 1,600 lines of HTML, CSS and JavaScript and a 530-line Python server (standard library only), no build step and no dependencies |
| Tooling scripts (Python, standard library only) | about 2,000 lines: the demo, the load-test runner, the crash tests, the OpenAPI check |

## Re-measuring

```bash
cd microservices
./mvnw verify                                              # every test; needs only Docker
python ../docs/api/check_openapi.py                        # the spec against the controllers
python demo/demo.py                                        # start everything and seed a merchant
python chaos/crash_and_outage_test.py                      # the seven crash and outage scenarios
cd load-test
python provision_keys.py --merchants 50
python run_load_test.py --threads 100 --rampup 20 --duration 180
python idempotency_replay_test.py --payments 1000 --order-keys 400 --merchants 20
```

Absolute numbers depend on the hardware; the relative effect of each fix should reproduce. See [running a load test](load-testing/running.md) and [scaling](deployment/scaling.md).
