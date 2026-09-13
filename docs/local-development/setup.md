# First-Time Setup

Assumes the [prerequisites](prerequisites.md) are installed. Commands use `./mvnw`; on Windows use `mvnw.cmd`.

## 1. Start PostgreSQL, Redis and Kafka

```bash
docker compose -f services.docker-compose.yaml up -d
```

This starts PostgreSQL on `5432`, Redis on `6380`, Kafka on `29092` (from the host) and Confluent Control Center on `9021`.

## 2. Create the four databases

Each business service has its own database on the same PostgreSQL server. Create them once:

```bash
docker exec -it pgvector-payflo psql -U user -d payflo-db -c "CREATE DATABASE payflo_merchant" -c "CREATE DATABASE payflo_payment" -c "CREATE DATABASE payflo_vault" -c "CREATE DATABASE payflo_operations"
```

Tables are created by Hibernate (`ddl-auto: update`) the first time each service starts.

## 3. Build every module

```bash
cd microservices && ./mvnw clean install -DskipTests
```

`install` puts `common-lib` into your local Maven repository, which every service depends on. Re-run it whenever `common-lib` changes.

## 4. Start the services in order

Each command blocks its terminal, so use one terminal per service, each **from its own module directory**:

```bash
cd microservices/discovery-service && ../mvnw spring-boot:run      # Eureka :8761 — first
cd microservices/config-service && ../mvnw spring-boot:run         # :8888 — second; resolves ../config-repo from here
cd microservices/merchant-service && ../mvnw spring-boot:run       # :8081
cd microservices/vault-service && ../mvnw spring-boot:run          # :8083
cd microservices/payment-service && ../mvnw spring-boot:run        # :8082
cd microservices/operations-service && ../mvnw spring-boot:run     # :8084
cd microservices/api-gateway-service && ../mvnw spring-boot:run    # :8080 — last
```

Discovery must be up before anything registers, and config-service before any service that imports its configuration. The four business services can start in any order. Check config-service is serving with `curl localhost:8888/payment-service/default`. If port 8080 is taken, start the gateway with `--server.port=<port>`.

## 5. Make a payment

Every call goes through the gateway on `:8080`. The dashboard-style calls use a JWT; the merchant-backend calls use an API key over HTTP Basic.

```bash
curl -s localhost:8080/v1/auth/signup -H 'Content-Type: application/json' \
  -d '{"name":"Acme","email":"owner@acme.test","password":"password123","businessName":"Acme Pvt Ltd","businessType":"PRIVATE_LIMITED"}'

TOKEN=$(curl -s localhost:8080/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"owner@acme.test","password":"password123"}' | jq -r .accessToken)

curl -s localhost:8080/v1/merchants/api-keys -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"environment":"TEST"}'
# → { "keyId": "fp_test_…", "keySecret": "…" } — the secret is shown only now
```

Then, as the merchant's backend (`-u keyId:keySecret`):

```bash
curl -s -u "$KEY_ID:$KEY_SECRET" localhost:8080/v1/orders -H 'Content-Type: application/json' \
  -d '{"amount":{"amountUnits":50000,"currency":"INR"},"receipt":"rcpt-1","customer":{"email":"buyer@example.com"}}'

curl -s -u "$KEY_ID:$KEY_SECRET" localhost:8080/v1/payments -H 'Content-Type: application/json' \
  -d '{"orderId":"<order id>","method":"UPI","methodDetails":{"vpa":"buyer@okaxis"}}'
```

The payment comes back `AUTHORIZING`. Within a few seconds the bank callback simulator authorizes and captures it: the payment moves to `CAPTURED`, the order to `PAID`, and `PAYMENT_STATUS_CHANGED` is published to Kafka (visible in Control Center at <http://localhost:9021>). For a card payment, tokenize the card first — see [vault](../api/vault.md) and the [mock acquirer](../api/mock-acquirer.md) test values.

## Local URLs

| Process | URL |
|---|---|
| Gateway (the only port clients call) | <http://localhost:8080> |
| Eureka dashboard | <http://localhost:8761> |
| config-service | <http://localhost:8888/<service>/default> |
| merchant / payment / vault / operations (direct) | `:8081` / `:8082` / `:8083` / `:8084` |
| Confluent Control Center | <http://localhost:9021> |

Calling a service directly skips the gateway, so no identity headers are set and the request runs with no merchant. That is useful only for the `/internal/**` endpoints and health checks.
