# Tracing

Every service records spans with Micrometer Tracing (Brave bridge) and reports them to Zipkin, so one request can be followed from the gateway through every service it touches.

## What produces spans

| Hop | Instrumented by |
|---|---|
| Incoming HTTP request (every service) | Spring MVC observation — one server span per request |
| Gateway → service (the proxied call) | The gateway's HTTP client — a client span, with the trace context passed on in `traceparent`/`b3` headers |
| Service → service over Feign (payment → vault/merchant, operations → merchant/payment, gateway → merchant) | `feign-micrometer` in the three services that make Feign calls |
| Redis commands (API-key cache, rate limiting, idempotency) | Lettuce — the gateway's `get`/`exists`/`incr` show up as short client spans |
| Kafka publish → consume | `spring.kafka.template.observation-enabled` (payment, operations) and `listener.observation-enabled` (operations): the trace context travels in the record headers |

So a payment request shows up as one trace: gateway server span → Redis lookups for the API key and rate limit → proxied call → payment-service's `POST /v1/payments` → Feign call to vault-service (for a card).

Background work starts its own traces. An outbox poll is one trace that continues into Kafka and into operations-service's consumer and its Feign call to merchant-service — the path a webhook takes.

## Configuration

Set in `config-repo/application.yaml` for every service:

| Setting | Default | Env var |
|---|---|---|
| `management.tracing.sampling.probability` | `1.0` — every request | `TRACING_SAMPLING_PROBABILITY` |
| `management.zipkin.tracing.endpoint` | `http://localhost:9411/api/v2/spans` | `ZIPKIN_URL` |

Spans are sent over plain `HttpURLConnection` (`common-lib`'s `SharedTracingAutoConfiguration`), so tracing doesn't depend on which HTTP client a service happens to have. If nothing is listening at `ZIPKIN_URL`, spans are dropped with one warning in the log and the service is otherwise unaffected.

Tracing every request is right for development. Under load it isn't: lower the rate (e.g. `TRACING_SAMPLING_PROBABILITY=0.1`) so span reporting doesn't compete with the requests being measured.

## Logs

With tracing on, every log line carries `[<application>] [<thread>] [<traceId>-<spanId>]`:

```
INFO 57780 --- [payment-service] [mcat-handler-13] [6ab82cb80a10701ed7ac12c3524662e8-66df510519e5a491] c.p.p.p.s.PaymentServiceImpl : ...
```

To go from a log line to the whole request, search Zipkin for that trace id; to go the other way, grep every service's log for it.

## Finding a trace

In the Zipkin UI, pick a service and span name (e.g. `api-gateway-service`, `http post /v1/payments/**`) and run the query, or paste a trace id from a log. The API works too:

```bash
curl -s "localhost:9411/api/v2/traces?serviceName=api-gateway-service&limit=10"
```
