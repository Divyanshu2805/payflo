# PayFlo

A payment processing backend built with Spring Boot. Currently in early development.

## Tech Stack

- Java 25
- Spring Boot 4.1.0 (Spring MVC, Spring Data JPA)
- PostgreSQL
- Lombok
- Maven

## Getting Started

Requires JDK 25 and a PostgreSQL instance.

```bash
./mvnw.cmd clean compile
```

```bash
./mvnw.cmd spring-boot:run
```

```bash
./mvnw.cmd test
```

## Project Status

Domain model (see the Entity Relationship Diagram in [docs/schema.md](docs/schema.md)) is
designed; implementation (entities, repositories, controllers, datasource config) is in progress.

## Documentation

Full project documentation — tech stack, domain model/ER diagram, APIs, and practices — is kept up to
date in [docs/](docs/README.md).
