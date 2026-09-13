# Coding Conventions

New work goes into `microservices/`. The monolith at the repository root is frozen — don't add features to it.

## Layering

Controllers orchestrate, services decide, repositories query. A controller never calls a repository directly, and a repository never contains business logic. The full per-package rules are in the [module map](../architecture/module-map.md#inside-a-business-service).

## Entities

- Extend `common-lib`'s `BaseEntity`; ids are `@GeneratedValue(strategy = GenerationType.UUID)`; annotate with `@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder`.
- **Every field with a default value needs `@Builder.Default`**, or the builder yields `null`. Read the compiler warning.
- Enums are `@Enumerated(EnumType.STRING)` with an explicit column `length`, and live in `common-lib`.
- Money is the `Money` embeddable, never a bare numeric column.
- **No cross-service relations.** A reference into another service's data is a plain UUID with no `@ManyToOne`. See [schema conventions](../schema/conventions.md).

## DTOs

- Request and response DTOs are Java `record`s in `dto/request` and `dto/response`.
- Validation annotations (`@NotNull`, `@Size`, `@Email`, …) sit on request fields, each with a `message`, enforced with `@Valid` on the controller parameter. Response records carry none.
- Contracts between services are records in `common-lib`'s `dto` package, so a change is a compile error on both sides.

## Mapping

Entity ↔ DTO conversion uses MapStruct interfaces in `mapper` (`componentModel = SPRING`). MapStruct silently leaves a field `null` when source and target names differ — `OrderMapper` needs an explicit `@Mapping` from `orderStatus` to `status` for exactly this reason. Fix every "Unmapped target property" warning.

## Exceptions

Throw an existing typed exception from `common-lib`'s `exception` package — `ResourceNotFoundException`, `DuplicateResourceException`, `BusinessRuleViolationException`, `InvalidStateTransitionException` — each carrying an `errorCode`. A bare `RuntimeException` surfaces as a `500 INTERNAL_ERROR`. A new exception type needs a handler in `GlobalExceptionHandler`. See the [error reference](../api/errors.md).

## Merchant scoping

Controllers read the merchant only from `MerchantContext` and pass it down; never take a merchant id from a path, query or body. Repository methods that read merchant data take the merchant id (`findByIdAndMerchantId`).

## Cross-service calls

- Put the endpoint in an `Internal*Controller` under `/internal/**`, the client in the caller's `client/`, and a shared DTO in `common-lib`.
- Annotate the client method with Resilience4j `@CircuitBreaker` and `@Retry`, with an instance configured in `config-repo`.
- **Never call another service inside a `@Transactional` method.** Resolve what you need first, then open the transaction (`OrderServiceImpl` → `OrderPersistenceService`), or split the work into a saga (`PaymentAuthorizationRecorder`).
- A sealed interface crossing Feign needs `@JsonTypeInfo`.

## Events and scheduled jobs

- Publish an event only by writing an outbox row in the same transaction as the change (`OutboxEventPublisher`). Never send to Kafka from a request.
- Every `@Scheduled` method carries a ShedLock `@SchedulerLock` with sensible `lockAtMostFor` / `lockAtLeastFor`.
- Change a payment's status only through `PaymentTransitionService.apply`.

## Configuration

- New settings go in `microservices/config-repo/<service>.yaml` (and `<service>-k8s.yaml` if they differ in-cluster), never in a module's `application.yaml`.
- A secret is a `${ENV_VAR:dev-default}` placeholder. The dev default must be obviously dev-only, and the variable must be added to `k8s/secrets.env.example`.
- A new `common-lib` bean is registered in the matching `Shared*AutoConfiguration`; `common-lib` is not component-scanned.

## Things to avoid

- **Adding a feature to the monolith.** It is the frozen reference.
- **A `SecurityFilterChain` in a business service.** Authentication belongs to the gateway.
- **Reading another service's database**, or adding a foreign key across services.
- **Logging a card number, CVV, secret or token.** vault-service logs only the first four characters of a token.
- **Committing `k8s/secrets.env`** or a real secret value anywhere.
