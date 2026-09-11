# The Monolith

PayFlo was first built as a single Spring Boot application, which still lives at the repository root (`src/`, `pom.xml`, package `com.project.payflo`). It was **frozen** when the microservices split began and is kept as the reference implementation the services were extracted from — see [decision 0001](../architecture.md). Don't add features to it.

## Running it

It uses the same local infrastructure as the services, but one database, `payflo-db`, which the compose file creates:

```bash
docker compose -f services.docker-compose.yaml up -d
```

```bash
./mvnw clean compile
```

```bash
./mvnw spring-boot:run
```

It listens on `:8080`, so don't run it alongside the gateway.

## Testing it

`PayFloApplicationTests.contextLoads` is a full `@SpringBootTest`, so it needs PostgreSQL running. There is no H2 or Testcontainers fallback.

```bash
./mvnw test -Duser.timezone=Asia/Kolkata
```

**The `-Duser.timezone` flag is required.** Without it the JVM sends the legacy `Asia/Calcutta` zone name and PostgreSQL rejects the connection.

## How it differs from the services

| Concern | Monolith | Microservices |
|---|---|---|
| Authentication | Two `SecurityFilterChain`s — JWT for `/v1/merchants/**`, API key for `/v1/orders/**` etc. | Once, at the gateway; services have no filter chain |
| Refresh tokens | `POST /v1/auth/refresh` and `/logout`, with a `refresh_token` table | Not carried over |
| Order reads | `GET /v1/orders/{id}`, `POST …/cancel`, `GET …/payments` | Not carried over |
| Payment methods | Card, net banking, UPI, wallet | Card, net banking, UPI (`WALLET` has no adapter) |
| Bank callback simulator | Built but not scheduled — payments stay `AUTHORIZING` | Scheduled — payments reach `CAPTURED` |
| Settlement | Not built | Built |
| Kafka | Producer and consumer in the same JVM | Across service boundaries |

The endpoints that exist only in the monolith are tracked in [known gaps](../gaps.md).
