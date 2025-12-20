# Tech Stack

[← Back to docs index](README.md)

- **Language:** Java 25
- **Framework:** Spring Boot 4.1.0
- **Persistence:** Spring Data JPA, PostgreSQL (local dev via `application.yaml`, schema auto-created)
- **Cache / rate limiting:** Redis via Spring Data Redis (`StringRedisTemplate`, Lua scripts for the atomic limiters)
- **Eventing:** Apache Kafka via `spring-boot-starter-kafka` — transactional outbox for domain events, consumed for webhook delivery
- **Build tool:** Maven
- **Other libraries:** Lombok, MapStruct (entity↔DTO mapping), Jakarta Bean Validation
