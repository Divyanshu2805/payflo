# PayFlo Documentation

Everything about how PayFlo is built, run and changed. Start with the section that matches what you're trying to do.

## Getting started

- [The demo and the dashboard](local-development/demo-and-dashboard.md) — one command that starts everything and seeds a merchant, and a web dashboard over the API.
- [Local development](local-development/README.md) — prerequisites, configuration, running the services, troubleshooting.
- [Tech stack](tech-stack.md) — the languages, frameworks and services in use.

## Understanding the system

- [What was built and what it measured](project-summary.md) — the idea, each design decision and optimisation, and its effect. Start here.
- [Requirements](requirements.md) — what the system set out to do, and what it measured.
- [Architecture](architecture/README.md) — services, module map, request flows, security model.
- [Architecture decisions](architecture/decisions/README.md) — why the system is shaped the way it is.
- [Data model](schema/README.md) — the four databases, their entities, the state machines, and schema conventions.

## Operating it

- [Observability](observability/README.md) — tracing across services, metrics, and the queries for throughput, latency and availability.
- [Crash and outage tests](reliability/crash-and-outage-tests.md) — what happens to a payment when a service is killed or PostgreSQL, Redis or Kafka goes away, and how that was checked.
- [Load testing](load-testing/README.md) — the JMeter plan, how to run it, how each non-functional target is measured, and the [results](load-testing/results.md).

## Reference

- [API reference](api/README.md) — every endpoint, the mock acquirer's test values, idempotency, rate limits and errors; the [OpenAPI spec](api/openapi.yaml) and a Postman collection.
- [Design trade-offs and scope](architecture/trade-offs.md) — what the design accepts, and what the project leaves out.

## Contributing

- [Engineering practices](practices/README.md) — conventions, security guardrails, testing, definition of done, and known pitfalls.
- [`CONTRIBUTING.md`](../CONTRIBUTING.md) — the contribution workflow.

## Running on Kubernetes

- [Deployment](deployment/README.md) — the kind topology, manifests, container images and configuration.

## Keeping these docs accurate

These pages describe the system as it is now. A change that makes any of them inaccurate updates them in the same commit. Diagrams are generated from [`assets/diagrams/src/`](assets/diagrams/README.md) — regenerate them rather than editing the images.
