# Tech Stack

Versions are the ones pinned in the `pom.xml` files, `services.docker-compose.yaml` and the Kubernetes manifests.

## Backend

| Technology | Version | Used for |
|---|---|---|
| Java | 25 | Every service; virtual threads for webhook delivery and settlement |
| Spring Boot | 4.1 | Web MVC, Data JPA, Validation, Actuator, Kafka, Data Redis |
| Spring Security Crypto | — | bcrypt for passwords and API-key secrets, AES-256-GCM for card data and webhook secrets — there is no Spring Security filter chain anywhere |
| Spring Cloud | 2025.1.2 | Gateway Server Web MVC, Netflix Eureka, Config Server (native backend), OpenFeign (Apache HttpClient 5) |
| Resilience4j | via Spring Cloud | Circuit breakers and retries on every Feign client; a thread-pool bulkhead around card charging |
| PostgreSQL | 18 locally, 16 on Kubernetes | One database per service |
| Hibernate | via Spring Data JPA | Schema managed with `ddl-auto: update` — no migration tool yet |
| Spring Data Redis | — | API-key cache, rate-limit counters, idempotency keys, the webhook retry queue, ShedLock |
| Apache Kafka | Confluent local 7.5 (KRaft) | Domain events published through the transactional outbox |
| ShedLock | 6.9 (Redis provider) | Every `@Scheduled` job runs on one instance only |
| jjwt | 0.12.6 | Issuing JWTs in merchant-service, verifying them at the gateway |
| Lombok, MapStruct | MapStruct 1.6.3 | Boilerplate and entity ↔ DTO mapping |
| Jakarta Bean Validation | — | Request DTO validation, including `@LuhnCheck` on card numbers |
| Maven | Wrapper (`mvnw`) | An aggregator build over eight modules |

## Infrastructure

| Technology | Used for |
|---|---|
| Docker Compose | Local PostgreSQL, Redis, Kafka and Confluent Control Center |
| Jib | Container images for every deployable module, without Dockerfiles |
| Kubernetes — kind | The whole system in a local cluster |
| Kustomize | Assembling the manifests and generating the Secret from `secrets.env` |
| Kafka UI | Browsing topics in the cluster |

## Documentation

Diagrams are generated SVG and PNG from Python (`docs/assets/diagrams/src/`), rendered with headless Chrome or Edge.
