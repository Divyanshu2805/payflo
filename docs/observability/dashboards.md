# Running Zipkin, Prometheus and Grafana

`microservices/observability/` holds a ready-made stack for the services running on your machine: Zipkin for traces, Prometheus scraping every service, and Grafana with a provisioned PayFlo dashboard.

## Start it

With the services running (see [local setup](../local-development/setup.md)):

```bash
docker compose -f microservices/observability/docker-compose.yaml up -d
```

| Tool | URL | Notes |
|---|---|---|
| Zipkin | <http://localhost:9411> | The services report here by default (`ZIPKIN_URL`) |
| Prometheus | <http://localhost:9090> | Scrapes every service every 5 s through `host.docker.internal` — `/targets` shows each one `up` or why not |
| Grafana | <http://localhost:3000> | Opens on the PayFlo dashboard; anonymous users can view, `admin` / `admin` to edit |

Prometheus's targets are in `observability/prometheus/prometheus.yml`: the gateway on its management port `9081` and the business services on `8081`–`8084`. If you run a service on another port, change it there.

## The PayFlo Overview dashboard

Provisioned from `observability/grafana/dashboards/payflo-overview.json`, refreshing every 5 s.

| Row | Panels |
|---|---|
| **Targets** | Gateway throughput, gateway p99 latency, gateway availability (share of non-5xx responses) and the share of requests under 1 s — each coloured against the [requirement](../requirements.md#non-functional) it tracks |
| **Traffic and latency by service** | Request rate and p99 per service, the gateway's p50/p95/p99, 5xx rate per service, and the five slowest endpoints |
| **Payments and events** | Payment transitions by resulting status, the outbox backlog, webhook deliveries by outcome |
| **Runtime** | Heap, database connections in use and waiting (HikariCP), CPU |

Every panel is a PromQL query from [metrics](metrics.md), so anything on the dashboard can also be run by hand in Prometheus. Grafana also has Zipkin as a datasource, for looking up a trace from the same place.

The dashboard is provisioned read-only: edit it in Grafana, export the JSON, and replace the file to keep a change.

## Stop it

```bash
docker compose -f microservices/observability/docker-compose.yaml down
```

Nothing is persisted: traces are in Zipkin's memory, and Prometheus keeps its data inside the container.
