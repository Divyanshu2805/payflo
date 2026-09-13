# Contributing to PayFlo

## Before you start

- Get the stack running locally: [local development](docs/local-development/README.md).
- Skim the [architecture overview](docs/architecture/README.md) to find which service owns the behaviour you're changing, and [where do I change…?](docs/architecture/where-to-change.md) to find the code.
- Read the [security guardrails](docs/practices/security-guardrails.md). Changes that weaken one are not accepted as a workaround; raise the problem instead.
- New work goes into `microservices/`. The monolith at the repository root is frozen.

## Workflow

1. Branch from `main`.
2. Make the change.
3. Run the checks below.
4. Open a pull request against `main`.

## Checks

```bash
# Build every module, with no new compiler warnings
cd microservices && ./mvnw clean install -DskipTests

# Prove the touched services start, then exercise the flow through the gateway
cd microservices/<module> && ../mvnw spring-boot:run
```

The full checklist is the [definition of done](docs/practices/definition-of-done.md), and what to verify by hand is in [testing](docs/practices/testing.md).

## Conventions

- **Code style** — see [coding conventions](docs/practices/coding-conventions.md). In short: controllers → services → repositories; request DTOs are validated records; errors are typed exceptions from `common-lib`; the merchant comes only from `MerchantContext`.
- **Between services** — Feign to `/internal/**` with a circuit breaker and retry, never inside a transaction; events only through the outbox.
- **Configuration** goes in `microservices/config-repo/`, never in a module's `application.yaml`.
- **Secrets** are environment variables with obviously-dev defaults. Never commit `microservices/k8s/secrets.env` or a real value.
- **Docs** — a change that makes any page in `docs/` inaccurate updates it in the same pull request, and a diagram it affects is regenerated from `docs/assets/diagrams/src/`.

## Commit messages

One line, in [Conventional Commits](https://www.conventionalcommits.org/) style:

```
feat: add refunds to payment-service
fix: scope the vault charge to the paying merchant
docs(api): document the capture endpoint
chore: bump Spring Cloud to 2025.1.3
```

Common types: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`. An optional scope names the area.

## Known pitfalls

Several traps in this stack fail silently — a Lombok builder dropping a default, a MapStruct field left `null`, a sealed type Jackson can't read across Feign. If something "should work" but doesn't, check [known pitfalls](docs/practices/gotchas/README.md) first.
