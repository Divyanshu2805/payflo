# PayFlo Documentation

Everything about how PayFlo is built, run and changed. Start with the section that matches what you're trying to do.

## Getting started

- [Local development](local-development/README.md) — prerequisites, configuration, running the services, troubleshooting, and the frozen monolith.
- [Tech stack](tech-stack.md) — the languages, frameworks and services in use.

## Understanding the system

- [Requirements](requirements.md) — what the system is designed to do, and the targets it's designed for.
- [Architecture](architecture/README.md) — services, module map, request flows, security model.
- [Architecture decisions](architecture/decisions/README.md) — why the system is shaped the way it is.
- [Data model](schema/README.md) — the four databases, their entities, the state machines, and schema conventions.

## Reference

- [API reference](api/README.md) — every endpoint, the mock acquirer's test values, idempotency, rate limits and errors.
- [Known gaps](known-gaps/README.md) — constraints, trade-offs, and what isn't built yet.

## Contributing

- [Engineering practices](practices/README.md) — conventions, security guardrails, testing, definition of done, and known pitfalls.
- [`CONTRIBUTING.md`](../CONTRIBUTING.md) — the contribution workflow.

## Running on Kubernetes

- [Deployment](deployment/README.md) — the kind topology, manifests, container images and configuration.

## Keeping these docs accurate

These pages describe the system as it is now. A change that makes any of them inaccurate updates them in the same commit. Diagrams are generated from [`assets/diagrams/src/`](assets/diagrams/README.md) — regenerate them rather than editing the images.
