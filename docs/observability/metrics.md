# Metrics

Every service exposes Micrometer metrics in Prometheus format at `/actuator/prometheus` (the gateway on its management port `9081`). Every series carries an `application` label with the service name.

## What is measured

| Metric | Source | Use |
|---|---|---|
| `http_server_requests_seconds_*` | Spring MVC, per `application`, `method`, `uri`, `status` | Throughput, latency percentiles, error rate |
| `jvm_memory_*`, `jvm_gc_*`, `jvm_threads_*`, `process_cpu_usage`, `system_cpu_usage` | JVM and process | Heap, GC, CPU — including whether the whole machine is saturated |
| `hikaricp_connections_active` / `_pending` / `_timeout_total` | HikariCP | Database pool use: `pending > 0` means requests are waiting for a connection |
| `resilience4j_circuitbreaker_state` | Resilience4j | Which breakers are open |
| `kafka_producer_*`, `kafka_consumer_*` | Kafka clients | Producer throughput, consumer lag |

### PayFlo's own metrics

| Metric | Service | Tags | Meaning |
|---|---|---|---|
| `payflo_payment_transitions_total` | payment | `from`, `event`, `to` | Every payment state-machine transition, counted in `PaymentTransitionService` — so `sum by (to) (rate(...))` is payments reaching each status per second. One series per edge of the [state machine](../schema/enums.md), so the label set is bounded |
| `payflo_outbox_pending` | payment | — | Outbox rows written but not yet published to Kafka (`OutboxMetrics`). It should hover near zero; a value that keeps growing means the poller or Kafka can't keep up, and webhooks and settlement are falling behind real time |
| `payflo_webhook_deliveries_total` | operations | `outcome` = `delivered`, `retry`, `dead` | Every webhook delivery attempt's result, counted in `WebhookDeliverExecutor`. `dead` means the event exhausted its attempts and went to the DLQ |
| `payflo_webhook_delivery_latency_seconds` | operations | `retried` = `true`, `false` | A histogram of how long after the change (the outbox row's creation time) the merchant's endpoint accepted its webhook (`WebhookMetrics`). Buckets are 0.25, 1, 2, 5, 10, **30**, 60 s, 5 min, 1 h and **24 h**, the two lines of the webhook SLA ("99% within 30 s, 100% within 24 h"): the share delivered in time is `sum(increase(…_bucket{le="30.0"}[5m])) / sum(increase(…_count[5m]))`, with `payflo_webhook_deliveries_total{outcome="dead"}` added to the denominator for events that never arrived. A delivery that needed a retry is counted under `retried="true"` and is, by the back-off schedule, over 30 s |
| `payflo_webhook_pending` | operations | — | Webhook events still waiting to be delivered (status `PENDING`, including those in flight) |
| `payflo_webhook_oldest_unattempted_age_seconds` | operations | — | How long the oldest event still waiting for its **first** attempt has been due. The latency histogram can't show an event until it is delivered; this is the SLA being missed right now. It should stay well under 30 |
| `payflo_velocity_refused_total` | vault, payment | `rule` = `tokenize-per-minute`, `tokenize-per-hour`, `card-testing`, `order-card-attempts` | Every request refused by a [card-testing velocity guard](../api/idempotency-and-rate-limits.md#velocity-limits-and-card-testing). A steady trickle for one rule is a merchant being throttled; a burst of `card-testing` is someone running stolen numbers |

These are the business-level signals the HTTP metrics can't show: a payment request can succeed while payments pile up in `AUTHORIZING`, or while events queue in the outbox.

### Latency histograms

`config-repo/application.yaml` turns on histogram buckets for `http.server.requests` and adds service-level-objective buckets at 100 ms, 250 ms, 500 ms and **1 s**:

```yaml
management.metrics.distribution:
  percentiles-histogram.http.server.requests: true
  slo.http.server.requests: 100ms,250ms,500ms,1s
```

Buckets (rather than percentiles computed inside each JVM) let Prometheus aggregate latency across instances and compute any percentile afterwards, and the 1 s bucket answers "what share of requests met the p99 < 1 s target" directly.

## Queries for the requirements

The [requirements](../requirements.md#non-functional) set throughput, latency and availability targets. Measured at the gateway — what a client experiences — excluding Actuator's own traffic:

| Target | PromQL |
|---|---|
| Throughput (req/s) | `sum(rate(http_server_requests_seconds_count{application="api-gateway-service",uri!~"/actuator.*"}[1m]))` |
| p99 latency | `histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{application="api-gateway-service",uri!~"/actuator.*"}[1m])))` |
| Availability (share without a 5xx) | `1 - sum(rate(http_server_requests_seconds_count{application="api-gateway-service",status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count{application="api-gateway-service"}[5m]))` |
| Share of requests under 1 s | `sum(rate(http_server_requests_seconds_bucket{application="api-gateway-service",le="1.0"}[5m])) / sum(rate(http_server_requests_seconds_count{application="api-gateway-service"}[5m]))` |

Swap `application` or group `by (application, uri)` to find which service or endpoint is responsible. Comparing a route's latency at the gateway with the same route inside the owning service shows how much time is spent in the gateway itself.

## Reading one service directly

```bash
curl -s localhost:8082/actuator/prometheus | grep '^hikaricp_connections'
curl -s localhost:9081/actuator/prometheus | grep '^http_server_requests_seconds_count'
```
