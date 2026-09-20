# Kubernetes Manifests

Everything lives in `microservices/k8s/` as plain manifests assembled with Kustomize (`kubectl apply -k microservices/k8s`).

```
microservices/k8s/
  kustomization.yaml       the resource list, and the secretGenerator for app-secrets
  kind-config.yaml         a single-node cluster named payflo; NodePort 30080 → localhost:8080
  secrets.env.example      copy to secrets.env (gitignored)
  infra/
    namespace.yaml         namespace payflo
    configmap.yaml         app-config — profile, config server URL, hosts, *_SERVICE_URI
  stateful/
    postgres.yaml          PostgreSQL 16 StatefulSet + init script for the four databases and users
    redis.yaml             Redis 7 StatefulSet
    kafka.yaml             single-node KRaft broker (confluent-local 7.5) + headless Service
    kafka-ui.yaml          Kafka UI
  services/
    config-service.yaml, merchant-service.yaml, vault-service.yaml,
    payment-service.yaml, operations-service.yaml, api-gateway-service.yaml

microservices/observability/   included from k8s/kustomization.yaml as ../observability
  kustomization.yaml       ConfigMaps generated from the files below, shared with the local stack
  k8s/zipkin.yaml, k8s/prometheus.yaml, k8s/grafana.yaml
  prometheus/prometheus-k8s.yml   in-cluster scrape targets
  grafana/                 datasources, dashboard provider and the PayFlo dashboard
```

## Stateful components

| Component | Kind | Storage | Notes |
|---|---|---|---|
| `postgres` | StatefulSet | 10Gi PVC | `max_connections=300`. An init script (a ConfigMap mounted at `/docker-entrypoint-initdb.d`) creates `payflo_merchant`, `payflo_payment`, `payflo_vault` and `payflo_operations`, each with its own user (`merchant_user`, …) holding privileges on that database only |
| `redis` | StatefulSet | 2Gi PVC | |
| `kafka` | StatefulSet | 10Gi PVC | Single-node KRaft broker; headless Service `kafka:9092` |
| `kafka-ui` | Deployment | — | `kubectl -n payflo port-forward svc/kafka-ui 8090:8090` |

## Application workloads

Every application is one Deployment and one Service with the same shape:

- image `payflo/<module>:latest` with `imagePullPolicy: IfNotPresent`, so images loaded into kind are used as-is;
- environment from the `app-config` ConfigMap, plus **only the secrets that service needs** from `app-secrets`;
- requests `250m` CPU / `512Mi`, limits `1` CPU / `1Gi`;
- startup (up to 5 minutes), readiness and liveness probes on `/actuator/health` — for the gateway, on its management port `9081`, since its public port answers `401` to anything without credentials.

| Service | Secrets it gets | Service type |
|---|---|---|
| `config-service` | — | ClusterIP; runs with `SPRING_PROFILES_ACTIVE=native,k8s` ([why](../practices/gotchas/kubernetes.md#config-service-needs-nativek8s-not-just-k8s)) |
| `merchant-service` | `JWT_SECRET` (to sign tokens), `WEBHOOK_SECRET_KEY`, its DB password | ClusterIP |
| `vault-service` | `VAULT_MASTER_KEY` — the only pod that gets it — and its DB password | ClusterIP |
| `payment-service` | its DB password | ClusterIP |
| `operations-service` | its DB password | ClusterIP |
| `api-gateway-service` | `JWT_SECRET` (to verify tokens) | **NodePort `30080`** — the only Service reachable from outside. A second, ClusterIP Service `api-gateway-management` exposes its management port `9081` (health, metrics) inside the cluster only |

`discovery-service` isn't deployed: the `k8s` profile turns Eureka off and services address each other through Kubernetes Services.

## Observability

| Component | Kind | Notes |
|---|---|---|
| `zipkin` | Deployment | In-memory storage — traces are lost on restart. Every service reports to `http://zipkin:9411` (`ZIPKIN_URL` in the ConfigMap) |
| `prometheus` | StatefulSet, 5Gi PVC | Scrapes every service every 10 s by Service name — the business services on port 80, the gateway through `api-gateway-management:9081`. Keeps 7 days |
| `grafana` | Deployment | Datasources, dashboard provider and the PayFlo dashboard mounted from ConfigMaps generated from `microservices/observability/`; admin password from `GRAFANA_ADMIN_PASSWORD` in `app-secrets` |

The ConfigMap also sets `TRACING_SAMPLING_PROBABILITY=0.1`, so the cluster traces one request in ten. See [observability](../observability/README.md).

## Start-up order

Kustomize applies everything at once. Ordering comes from the services themselves: each fails fast and retries (`SPRING_CLOUD_CONFIG_FAIL_FAST`, up to 20 attempts) until config-service answers, and the startup probe gives it time. Expect a few restarts on a first deploy.

## Not included

One replica per service, no Ingress controller, no HorizontalPodAutoscaler, no `NetworkPolicy`, no alerting rules, no image registry or CI — see [known gaps](../known-gaps/not-yet-built.md#platform).
