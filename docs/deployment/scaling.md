# Scaling Out

Does PayFlo scale horizontally? On the kind cluster, the two services that take the traffic, the **gateway** and **payment-service**, run as several replicas, and throughput rises with the count: **338 → 758 → 999 req/s with 1, 2 and 3 replicas of each**. This page is how to run it, what makes it safe, what was measured, and where it stops.

## Running it

```bash
kubectl apply -k microservices/k8s-scaled        # 3 gateways and 3 payment-services
kubectl -n payflo scale deploy/api-gateway-service deploy/payment-service --replicas=2     # or any other count
```

`microservices/k8s-scaled` is a Kustomize overlay on `microservices/k8s` (a sibling directory: Kustomize refuses an overlay inside the directory it extends). It sets the replica counts, and **gives the data tier room**: the base runs PostgreSQL on one CPU and 1.5 GiB, Redis on a quarter of a CPU and 256 MiB, and Kafka on one CPU, which is right for a demo and would cap any number of replicas at what that one PostgreSQL CPU can do. The overlay raises them to 4 CPUs and 3 GiB (with `shared_buffers=1GB`), 1 CPU and 512 MiB (`maxmemory 384mb`) and 2 CPUs and 1.5 GiB. Each replica keeps the base's limits of 1 CPU and 1 GiB.

## Why replicas are safe

| Concern | How it's handled |
|---|---|
| Gateway state | None in the gateway: API keys, rate-limit counters, failed-auth counts, token revocations and suspensions are all in Redis, so any replica answers any request |
| Two payment-service replicas touching one payment | State changes are row-locked (`SELECT … FOR UPDATE`) and go through `PaymentTransitionService`; payments and orders are unique per idempotency key in the database, not just Redis |
| Scheduled jobs running on every replica | Every `@Scheduled` job holds a ShedLock in Redis, so each runs on exactly one replica at a time |
| Losing a pod mid-request | A `RollingUpdate` with `maxUnavailable: 0`, a readiness probe, a 5 s `preStop` sleep so the Service stops sending before the pod stops serving, `server.shutdown: graceful` (20 s) and `terminationGracePeriodSeconds: 40` |
| A drain or upgrade taking too many at once | A `PodDisruptionBudget` of `maxUnavailable: 1` for each of the two |
| Replicas × database connections | Each payment-service holds up to 40 connections: 3 replicas use 120 of PostgreSQL's 300 (`max_connections`) with the other services' pools |

## What was measured

**200** virtual users (twice the local runs, to have enough load for three replicas), a 30-second ramp-up and 3 minutes, after a 1-minute warm-up of each size, through the NodePort on `localhost:8080`, on a fresh database. Everything is one machine: the load generator, Docker Desktop's network proxy and the kind node's pods share a 32-core laptop, and each replica is limited to **one CPU** by its Deployment, which is what the replicas add.

| Replicas of gateway and payment-service | Requests a second | Against 1 replica | p50 | p95 | p99 | Errors |
|---|---|---|---|---|---|---|
| 1 and 1 | **338** | 1.0× | 420 ms | 1,476 ms | 2,102 ms | 0 |
| 2 and 2 | **758** | 2.2× | 136 ms | 807 ms | 1,370 ms | 0 |
| 3 and 3 | **999** | 3.0× | 92 ms | 678 ms | 1,377 ms | 0 |

A short warm run at 3 + 3 reached 1,245 req/s at p99 659 ms. One replica sits at its 1-CPU limit (the gateway and payment-service pods both showed 100% of a core), so what two and three replicas add is mostly CPU: throughput scales close to linearly for the request path, and the node used about 11 of the host's 32 cores at three.

Read the latency column for what it is: 200 users sharing one CPU per service queue up, and a fresh JVM at the start of each size is cold. The p99 under 1 s that [the local runs](../load-testing/results.md) show (171–245 ms at 100 users, with no CPU limit) is not what a 1-CPU pod gives at this load. These numbers are for comparing 1, 2 and 3 against each other, not against the local ones: the database here is empty, the CPU is limited, and traffic crosses Docker's network proxy.

## What does not scale: the simulated bank

The bank-callback simulator (`BankCallbackSimulator`), which answers and captures payments, runs on **one** replica at a time (its ShedLock), so it is capped by one pod's CPU however many replicas there are:

| Replicas | Captures a second | Payments answered by the end of the run |
|---|---|---|
| 1 | 133 | 95% |
| 2 | 136 | 79% (backlog peak 8,700; 7,100 at the end of the run, and not cleared within the 4-minute wait) |
| 3 | 152 | the backlog of the run before was still being worked through |

It scaled on a laptop with no CPU limit (400–500 captures a second, 96% answered by the end, [results](../load-testing/results.md#capture-under-load)). This is the stand-in for an external bank, whose callbacks would reach any replica; it is the only part of the request-to-captured path that doesn't spread. Letting every replica resolve disjoint batches (`FOR UPDATE SKIP LOCKED` instead of one lock holder) is the change that would make capture scale with the replicas.

## Rolling out under load

With 3 + 3 replicas under 100 users for 200 seconds, `kubectl rollout restart` of the gateway and then of payment-service (each rolled one pod at a time, `maxUnavailable: 0`) cost **19 failed requests out of 226,834 (0.008%, availability 99.992%)**, with 1,133 requests a second throughout:

- **14 from the gateway rollout:** connections that were open through the NodePort when an old pod went away (`Connection reset`, `NoHttpResponseException`, one read timeout) — a client with keep-alive connections to a pod that stops sees a reset for the request in flight, whatever the `preStop` sleep. A client that retries an idempotent request (every write here takes an `X-Idempotency-Key`) loses nothing.
- **5 from the payment-service rollout:** `500`s on requests that were mid-flight on a pod as it terminated.

So a rollout is not perfectly lossless, but it is within the 99.99% availability target, and the database agrees nothing went wrong beyond those refused requests: after the run, across all 408,000 payments the cluster had made (the scaling runs included), no order had two live payments, no idempotency key was used twice, no payment was missing its transition log, none was stuck `AUTHORIZING`, and the outbox was empty. The slowest request took 30 s (one on a connection that was never closed). Closing the gap needs retries in the gateway for idempotent requests, which is not built.

## What this does not show

- **A real cluster.** All of the replicas run on one node, so a node failure takes all of them. Spreading them (`topologySpreadConstraints`) and a second node are what it would take.
- **Autoscaling.** There is no HorizontalPodAutoscaler (it needs a metrics server, which kind doesn't have by default); the count is set by hand.
- **The data tier.** PostgreSQL, Redis and Kafka are single instances. 3 + 3 replicas on one database is where this laptop's measurements stop being about the replicas.
- **More than three replicas, or a larger database.** Not measured.
