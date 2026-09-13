# Useful Commands

On Windows, use `mvnw.cmd` in place of `./mvnw`.

## Microservices (`microservices/`)

| Command | Purpose |
|---|---|
| `./mvnw clean install -DskipTests` | Build every module and install `common-lib` locally |
| `../mvnw spring-boot:run` (from a module directory) | Run one service — see the [start order](setup.md#4-start-the-services-in-order) |
| `./mvnw -pl common-lib,<module> compile` | Compile one service against `common-lib` from source |
| `./mvnw -pl <module> test` | That module's tests — a `contextLoads` that needs discovery, config and the infrastructure running |
| `./mvnw -DskipTests jib:dockerBuild -pl <module>` | Build a container image into the local Docker daemon ([container images](../deployment/container-images.md)) |

## Checking a running stack

| Command | Purpose |
|---|---|
| `curl localhost:8888/<service>/default` | The configuration config-service is serving to a service |
| `curl localhost:808x/actuator/health` | A service's health (`8080` gateway, `8081`–`8084` business services) |
| Eureka dashboard at <http://localhost:8761> | Which instances have registered |
| Control Center at <http://localhost:9021> | Topics and messages on the local Kafka |

## Infrastructure

| Command | Purpose |
|---|---|
| `docker compose -f services.docker-compose.yaml up -d` | Start PostgreSQL, Redis, Kafka and Control Center |
| `docker compose -f services.docker-compose.yaml down -v` | **Delete** all local data — read [resetting local data](resetting-data.md) first |
| `docker exec -it pgvector-payflo psql -U user -d payflo_payment` | A SQL shell on one service's database |
| `docker exec -it redis redis-cli -p 6379` | The Redis CLI (`KEYS apikey:*`, `ZRANGE …` for the webhook retry queue) |

## The monolith (repository root)

See [the monolith](the-monolith.md).
