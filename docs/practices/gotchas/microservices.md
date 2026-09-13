# Pitfalls: Microservices

## A sealed interface crossing Feign needs a type discriminator

- **Symptom:** every card payment failed with `PAYMENT_GATEWAY_ROUTER_UNREACHABLE`, caught by the saga's compensation.
- **Cause:** vault-service returns `PaymentProcessorResponse`, a sealed interface. Without type information in the JSON, Jackson on payment-service's side can't choose which record to build, and deserialization throws.
- **Fix:** `@JsonTypeInfo(use = NAME, property = "type")` with `@JsonSubTypes` for `PENDING` / `SUCCESS` / `FAILURE`. Any sealed type sent over Feign needs the same.

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
- **Fix:** resolve remote data before the transaction opens, or split the work into a saga — see [decision 0006](../../architecture/decisions/0006-payment-initiation-as-a-saga.md). Settlement still does this; see [known gaps](../../known-gaps/not-yet-built.md#settlement).

## Two property names for the same Kafka topic

- **Symptom:** events are published but the webhook consumer never sees some of them (the monolith hit this for refund and settlement events).
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
