# Useful Commands

On Windows, use `mvnw.cmd` in place of `./mvnw`.

## Microservices (`microservices/`)

| Command | Purpose |
|---|---|
| `python demo/demo.py` | Start everything, seed a demo merchant and serve the dashboard; `seed`, `status`, `stop`, `--build`, `--port-offset N` ([the demo](demo-and-dashboard.md)) |
| `python dashboard/serve.py` | Only the dashboard and Swagger UI, on <http://localhost:5173>, against a gateway that is already running |
| `./mvnw clean install -DskipTests` | Build every module and install `common-lib` locally |
| `../mvnw spring-boot:run` (from a module directory) | Run one service — see the [start order](setup.md#4-start-the-services-in-order) |
| `./mvnw -pl common-lib,<module> compile` | Compile one service against `common-lib` from source |
| `./mvnw -pl <module> test` | That module's unit and integration tests. The integration tests start PostgreSQL, Redis and Kafka themselves with Testcontainers, so they need only Docker: not the config server, not Eureka, not the compose stack |
| `./mvnw verify` | Every test in every module, as CI runs it |
| `python ../docs/api/check_openapi.py` | Check that `docs/api/openapi.yaml` names exactly the endpoints the controllers have |
| `python chaos/crash_and_outage_test.py` | The crash and outage tests (from `microservices/`; needs the whole stack running). See [the results](../reliability/crash-and-outage-tests.md) |
| `./mvnw -DskipTests jib:dockerBuild -pl <module>` | Build a container image into the local Docker daemon ([container images](../deployment/container-images.md)) |

## Checking a running stack

| Command | Purpose |
|---|---|
| `curl localhost:8888/<service>/default` | The configuration config-service is serving to a service |
| `curl localhost:808x/actuator/health` | A business service's health (`8081`–`8084`); the gateway's is on its management port, `localhost:9081/actuator/health` |
| `curl localhost:8082/actuator/prometheus` | A service's metrics in Prometheus format ([metrics](../observability/metrics.md)) |
| Eureka dashboard at <http://localhost:8761> | Which instances have registered |
| Control Center at <http://localhost:9021> | Topics and messages on the local Kafka |

## Infrastructure

| Command | Purpose |
|---|---|
| `docker compose -f services.docker-compose.yaml up -d` | Start PostgreSQL, Redis, Kafka and Control Center |
| `docker compose -f services.docker-compose.yaml down -v` | **Delete** all local data — read [resetting local data](resetting-data.md) first |
| `docker compose -f microservices/observability/docker-compose.yaml up -d` | Start Zipkin (`:9411`), Prometheus (`:9090`) and Grafana (`:3000`) — see [observability](../observability/dashboards.md) |
| `docker exec -it pgvector-payflo psql -U user -d payflo_payment` | A SQL shell on one service's database |
| `docker exec -it redis redis-cli -p 6379` | The Redis CLI (`KEYS apikey:*`, `ZRANGE …` for the webhook retry queue) |
