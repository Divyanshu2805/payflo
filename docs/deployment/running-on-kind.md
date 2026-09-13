# Running on kind

Requires Docker, kind, kubectl and JDK 25. Commands use `./mvnw`; on Windows use `mvnw.cmd`.

## 1. Build the images

Jib builds straight into the local Docker daemon:

```bash
cd microservices && ./mvnw -DskipTests install
```

```bash
cd microservices && ./mvnw -DskipTests jib:dockerBuild -pl config-service,merchant-service,vault-service,payment-service,operations-service,api-gateway-service
```

This produces `payflo/<module>:latest` for all six. If Docker Hub rejects the base-image pull with `401 Unauthorized`, see [the pitfall](../practices/gotchas/kubernetes.md#jib-gets-401-unauthorized-pulling-the-base-image).

## 2. Create the cluster and load the images

```bash
kind create cluster --config microservices/k8s/kind-config.yaml
```

```bash
for s in config-service merchant-service vault-service payment-service operations-service api-gateway-service; do kind load docker-image payflo/$s:latest --name payflo; done
```

`kind-config.yaml` creates a single-node cluster named `payflo` and publishes the gateway on `localhost:8080`; change `hostPort` there if 8080 is taken.

## 3. Create the secrets file

```bash
cp microservices/k8s/secrets.env.example microservices/k8s/secrets.env
```

`secrets.env` is gitignored. The example values are development-only — replace them for anything shared.

## 4. Deploy

```bash
kubectl apply -k microservices/k8s
```

```bash
kubectl -n payflo get pods -w
```

PostgreSQL, Redis, Kafka and config-service come up first. The other services fail fast and restart until config-service answers — a couple of restarts on the first deploy is expected. Everything is ready within a few minutes.

## 5. Use it

The gateway is the only thing reachable from outside: `http://localhost:8080` (or `kubectl -n payflo port-forward svc/api-gateway-service 8080:8080`). The same walkthrough as [local development](../local-development/setup.md#5-make-a-payment) works: signup → login → API key → order → payment, and the bank callback simulator moves the payment to `CAPTURED` within seconds.

```bash
kubectl -n payflo port-forward svc/kafka-ui 8090:8090                   # browse topics at localhost:8090
```

```bash
kubectl -n payflo exec -it postgres-0 -- psql -U postgres -d payflo_payment
```

```bash
kubectl -n payflo logs deploy/payment-service -f
```

## After a code change

Rebuild the image for the module you changed, load it again, and restart its Deployment — the manifests use `:latest` with `imagePullPolicy: IfNotPresent`, so a new image isn't picked up on its own:

```bash
kind load docker-image payflo/payment-service:latest --name payflo && kubectl -n payflo rollout restart deploy/payment-service
```

## Tear down

```bash
kind delete cluster --name payflo
```
