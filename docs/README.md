# PayFlo Documentation

Everything about what PayFlo does, how it is built, and how to run and change it. Start with the section that matches what you're trying to do.

## Getting started

- [Features](features.md) — everything the system does, by area, with its rules and limits.
- [The demo and the dashboard](local-development/demo-and-dashboard.md) — one command that starts everything and seeds a merchant, and a web dashboard over the API.
- [Local development](local-development/README.md) — prerequisites, setup, configuration, commands, troubleshooting.
- [Tech stack](tech-stack.md) — the languages, frameworks and services in use.

## Understanding the system

- [Architecture](architecture/README.md) — services, module map, service communication, request flows, security model.
- [Architecture decisions](architecture/decisions/README.md) — why the system is shaped the way it is (eight records).
- [Data model](schema/README.md) — the four databases, their tables, the enums and state machines, and schema conventions.

## Reference

- [API reference](api/README.md) — every endpoint, the mock acquirer's test values, idempotency, rate limits and errors; the [OpenAPI spec](api/openapi.yaml) and a Postman collection.
- [Project metrics](metrics.md) — measured performance, scaling, reliability, quality and security figures, and how to re-check them.

## Measuring and operating it

- [Load testing](load-testing/README.md) — the JMeter plan, how to run it, the [results](load-testing/results.md) run by run, and the [query review](load-testing/query-review.md).
- [Crash and outage tests](reliability/crash-and-outage-tests.md) — what happens to a payment when a service is killed or PostgreSQL, Redis or Kafka goes away.
- [Observability](observability/README.md) — tracing across services, metrics, and the Grafana dashboard.

## Running on Kubernetes

- [Deployment](deployment/README.md) — the kind topology, manifests, container images and configuration.
- [Scaling](deployment/scaling.md) — several replicas, rolling updates under load, and what was measured.

## Contributing

- [`CONTRIBUTING.md`](../CONTRIBUTING.md) — the workflow, the checks and the commit style.
- [Engineering practices](practices/README.md) — conventions, security guardrails, testing, definition of done, and known pitfalls.
- [`SECURITY.md`](../SECURITY.md) — reporting a vulnerability.

## Keeping these docs accurate

These pages describe the system as it is now. A change that makes any of them inaccurate updates them in the same commit. Diagrams are generated from [`assets/diagrams/src/`](assets/diagrams/README.md): regenerate them rather than editing the images.
