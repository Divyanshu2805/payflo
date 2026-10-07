# Pitfalls: Microservices

## A sealed interface crossing Feign needs a type discriminator

- **Symptom:** every card payment failed with `PAYMENT_GATEWAY_ROUTER_UNREACHABLE`, caught by the saga's compensation.
- **Cause:** vault-service returns `PaymentProcessorResponse`, a sealed interface. Without type information in the JSON, Jackson on payment-service's side can't choose which record to build, and deserialization throws.
- **Fix:** `@JsonTypeInfo(use = NAME, property = "type")` with `@JsonSubTypes` for `PENDING` / `SUCCESS` / `FAILURE`. Any sealed type sent over Feign needs the same.

## The gateway's proxy pool allows 5 connections per route

- **Symptom:** under load, requests through the gateway take hundreds of milliseconds longer than the same requests inside the owning service — in a load test, `/v1/orders` took ~25 ms in payment-service but ~370 ms at the gateway — while a rarely used route (vault) added only a few milliseconds.
- **Cause:** Spring Cloud Gateway Server Web MVC proxies with Apache HttpClient 5 (on the classpath through Eureka), whose pooling connection manager defaults to 5 connections per route. With 100 concurrent requests to one service, 95 queue in the gateway waiting for a connection. Metrics show it as latency at the gateway only; the service looks idle.
- **Fix:** `ProxyHttpClientConfig` in the gateway supplies a `ClientHttpRequestFactoryBuilder` with a bigger pool — `app.gateway.proxy.max-connections-per-route` (200) and `…max-connections-total` (1000). Switching the whole application to the JDK client (`spring.http.clients.imperative.factory: jdk`) is not a fix: it also changes the client Feign's decoder expects, and API-key lookups started failing with `'messageConverters' must not be empty`.
- **Watch for:** lifting this limit moves the queue downstream. The 5-connection pool had been quietly throttling traffic into payment-service; without it, payment-service's own database pool became the bottleneck — see [Open-Session-In-View](spring-and-jpa.md#open-session-in-view-holds-a-connection-across-remote-calls).

## `common-lib` beans must be registered, not scanned

- **Symptom:** a class added to `common-lib` never becomes a bean in the services.
- **Cause:** services don't component-scan `common-lib`'s packages. Its beans come from the `Shared*AutoConfiguration` classes listed in `META-INF/spring/…AutoConfiguration.imports`.
- **Fix:** declare the bean in the matching auto-configuration (or add a new one to the imports file).

## A stale `common-lib` jar

- **Symptom:** `NoClassDefFoundError` for a class you just added to `common-lib`, although everything compiled.
- **Cause:** a service started on its own resolves `common-lib` from `~/.m2`, which still holds the previous build.
- **Fix:** `./mvnw install` in `microservices/` after changing `common-lib`, or build as a reactor (`-pl common-lib,<module>`).

## config-service can't find `config-repo`

- **Symptom:** services start with missing properties, or fail on placeholders, although config-service is up.
- **Cause:** config-service reads `file:../config-repo` relative to its **working directory**. Started from the repository root or `microservices/`, that path points nowhere, and it serves empty property sources without failing.
- **Fix:** start it from `microservices/config-service`, or set `CONFIG_REPO_PATH`. Check with `curl localhost:8888/<service>/default`.

## A remote call inside a transaction

- **Symptom:** under load or when a peer is slow, the connection pool is exhausted and unrelated requests time out.
- **Cause:** a `@Transactional` method that calls another service holds a database connection, and any row locks, for the whole call.
- **Fix:** resolve remote data before the transaction opens, or split the work into a saga — see [decision 0006](../../architecture/decisions/0006-payment-initiation-as-a-saga.md). Settlement used to do this and no longer does: it is a sequence of short transactions (`SettlementRecorder`) with the remote calls between them (see [the settlement flow](../../architecture/flows/settlement.md)), and `NoRemoteCallInTransactionTest` fails if a bean with a transactional method in operations-service ever holds a client of another service.

## Two property names for the same Kafka topic

- **Symptom:** events are published but the webhook consumer never sees some of them (this happened to refund and settlement events before the split into services).
- **Cause:** `OutboxPoller` resolves topics through `KafkaProperties` keyed by the aggregate type (`app.kafka.topics.payment`), while `WebhookKafkaConsumer`'s listener reads plural keys (`app.kafka.topics.payments`).
- **Fix:** `config-repo/operations-service.yaml` sets both key sets to the same topic names. Change both together until the consumer is moved onto `KafkaProperties`.

## A missing `app.rate-limit.method` stops the gateway

- **Symptom:** the gateway fails to start with no `RateLimiter` bean.
- **Cause:** each limiter is `@ConditionalOnProperty(app.rate-limit.method = …)`; with the property unset, none exists.
- **Fix:** keep `app.rate-limit.method` set in `config-repo/api-gateway-service.yaml`.

## A scheduled job without ShedLock runs on every instance

- **Symptom:** duplicate webhook deliveries, a payment resolved twice, two settlements for one merchant — as soon as a service runs more than one instance.
- **Cause:** `@Scheduled` runs on every instance that has the bean.
- **Fix:** every `@Scheduled` method has a `@SchedulerLock` with a Redis lock provider; the service needs `@EnableSchedulerLock` and its `SchedularLockConfig`.

## A `LocalDateTime` Feign parameter needs `@DateTimeFormat` on the client too

- **Symptom:** a Feign call fails with `400 INVALID_PARAMETER` for a date parameter — every nightly settlement failed for every merchant, because the query string carried `capturedBefore=05/10/26, 8:21 pm`.
- **Cause:** the controller declared `@DateTimeFormat(iso = DATE_TIME)`, but the Feign interface's `@RequestParam` had no annotation, so Feign formatted the value with the JVM's default locale.
- **Fix:** put the same `@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)` on the Feign parameter (`PaymentServiceClient.findUnsettledCaptured`). Any date or time parameter crossing Feign needs it on both sides.

## A new path prefix needs a gateway route in both files

- **Symptom:** `404 ROUTE_NOT_FOUND` through the gateway for an endpoint that works on the service directly — `GET /v1/refunds` after `RefundController` was added.
- **Cause:** routes are an explicit `Path=` list in `api-gateway-service.yaml` and `api-gateway-service-k8s.yaml`; a controller on a new prefix is not routed until it is added to both.
- **Fix:** add the prefix to the owning service's route in both files.

## `'messageConverters' must not be empty` on the first concurrent Feign calls

- **Symptom:** right after a start, a Feign call fails with `DecodeException: 'messageConverters' must not be empty` from `SpringDecoder`, while other threads making the same call at the same moment succeed. It happened once, in the nightly settlement, where nine merchants call payment-service together.
- **Cause:** a race in Spring Cloud OpenFeign 5.0.2. `FeignHttpMessageConverters` assigned an empty list to its field and only then filled it, so a thread arriving in between saw a non-null, still-empty list. OpenFeign also builds one per Feign client, so each client had its own first-call window.
- **Fix:** fixed upstream in Spring Cloud OpenFeign 5.0.3 (Spring Cloud 2025.1.3): the field is `volatile` and the list is built under a lock. The workaround this project carried meanwhile was removed with the upgrade. `OpenFeignConverterRaceTest` in `common-lib` stays as a guard: it reproduces the race with a slow customizer and fails if the dependency is ever downgraded to a release that has it.

## Webhook deliveries run the machine out of ephemeral ports

- **Symptom:** under load, deliveries fail with `Address already in use`, and so do the load generator's own requests (`java.net.BindException`), because both are on the same machine.
- **Cause:** the webhook client was `HttpURLConnection`, which keeps five idle connections per host. At a few hundred deliveries a second almost every one opened a new TCP connection that then sat in `TIME_WAIT`.
- **Fix:** `WebhookClientConfig` sends with the JDK `HttpClient`, which keeps connections alive and shares them, at most 32 sends in flight (`app.webhook.delivery.http-concurrency`). It also does not follow redirects, so a merchant's server can't send a delivery past the URL check at delivery time; a `3xx` is a failed attempt.

## One transaction per Kafka record caps the consumer, and a single partition then falls behind

- **Symptom:** the webhook consumer's lag grows without bound (84,000 records in a minute) while the machine is mostly idle, and deliveries arrive minutes after the change.
- **Cause:** a record-at-a-time listener commits once per event. A few hundred events a second is all one thread can do that way, and the services produced a thousand.
- **Fix:** the listener takes whole polls (`spring.kafka.listener.type: batch`) and saves them in one transaction, falling back to one record at a time only when a batch can't be saved because of a bad record. Delivery batches the same way (`WebhookDeliveryScheduler`: claim a batch in one transaction, send concurrently, record the successes in one `UPDATE`).