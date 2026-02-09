# Tech Stack

[← Back to docs index](README.md)

- **Language:** Java 25
- **Framework:** Spring Boot 4.1.0
- **Persistence:** Spring Data JPA, PostgreSQL (local dev via `application.yaml`, schema auto-created)
- **Cache / rate limiting:** Redis via Spring Data Redis (`StringRedisTemplate`, Lua scripts for the atomic limiters)
- **Eventing:** Apache Kafka via `spring-boot-starter-kafka` — transactional outbox for domain events, consumed for webhook delivery
- **Build tool:** Maven
- **Other libraries:** Lombok, MapStruct (entity↔DTO mapping), Jakarta Bean Validation
- **Containers / orchestration:** Jib (`jib-maven-plugin`, base `eclipse-temurin:25-jre`), Kubernetes
  manifests assembled with Kustomize, kind for the local cluster
- **Microservices (phase 2):** Spring Cloud 2025.1 — Netflix Eureka (service discovery), Spring Cloud
  Config (native backend over the in-repo `microservices/config-repo`), Spring Cloud Gateway Server
  Web MVC (API gateway), OpenFeign (service-to-service HTTP, Apache HttpClient 5), Resilience4j
  (circuit breaker, retry, thread-pool bulkhead), ShedLock with a Redis provider (single-instance
  scheduled jobs), jjwt at the gateway for JWT verification
