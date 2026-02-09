# Deployment (Kubernetes)

[← Back to docs index](README.md)

How to run the PayFlo microservices on a local [kind](https://kind.sigs.k8s.io/) cluster. Everything
lives under `microservices/k8s/`; how each piece is built is described in
[Microservices → Deployment](microservices.md#deployment-kubernetes).

Requires Docker, kind, kubectl, and JDK 25.

## 1. Build the images

Jib builds straight into the local Docker daemon — no Dockerfiles:

```bash
cd microservices && ./mvnw.cmd -DskipTests install
```

```bash
cd microservices && ./mvnw.cmd -DskipTests jib:dockerBuild -pl config-service,merchant-service,vault-service,payment-service,operations-service,api-gateway-service
```

This produces `payflo/<module>:latest` for all six. If Docker Hub rejects the base-image pull with
`401 Unauthorized` (stale credentials in your Docker config), pull it once with Docker and point Jib
at the local copy: `docker pull eclipse-temurin:25-jre`, then add
`-Djib.from.image=docker://eclipse-temurin:25-jre` to the command above.

## 2. Create the cluster and load the images

```bash
kind create cluster --config microservices/k8s/kind-config.yaml
```

```bash
for s in config-service merchant-service vault-service payment-service operations-service api-gateway-service; do kind load docker-image payflo/$s:latest --name payflo; done
```

`kind-config.yaml` publishes the gateway on `localhost:8080`; change `hostPort` there if 8080 is
taken.

## 3. Secrets

```bash
cp microservices/k8s/secrets.env.example microservices/k8s/secrets.env
```

`secrets.env` is gitignored. The example values are dev-only — replace them for anything shared.

## 4. Deploy

```bash
kubectl apply -k microservices/k8s
```

```bash
kubectl -n payflo get pods -w
```

Postgres, Redis, Kafka, and config-service come up first; the other services fail fast and restart
until config-service answers (a couple of restarts on first deploy is expected). Everything is ready
in a few minutes.

## 5. Use it

The gateway is the only thing reachable from outside: `http://localhost:8080` (or
`kubectl -n payflo port-forward svc/api-gateway-service 8080:8080`). The same flow as local
development works: signup → login → API key → order → payment; the bank callback simulator moves the
payment to `CAPTURED` within seconds.

Useful while it's running:

```bash
kubectl -n payflo port-forward svc/kafka-ui 8090:8090
```

```bash
kubectl -n payflo exec -it postgres-0 -- psql -U postgres -d payflo_payment
```

```bash
kubectl -n payflo logs deploy/payment-service -f
```

## Tear down

```bash
kind delete cluster --name payflo
```

## What's verified

Deployed to a fresh kind cluster: all 10 pods healthy; signup, login, API key creation, order
creation (with customer resolution across services), card tokenization, and a card payment through
the gateway reaching `CAPTURED`, with `ORDER_CREATED`/`PAYMENT_CREATED`/`PAYMENT_STATUS_CHANGED`
outbox events published to the in-cluster Kafka.

## Not included yet

No Ingress controller (NodePort only), no Horizontal Pod Autoscalers or multiple replicas, no
observability stack (tracing, metrics, dashboards), no load tests, and no image registry/CI
pipeline — images are built and loaded locally.
