# 0008. Configuration in the repository, served by a native config server

**Status:** Accepted

## Context

Eight modules each need ports, datasources, Kafka and Redis settings, routes, resilience settings and secrets. Scattered across eight `application.yaml` files they drift, and a Git-backed config server would need a separate repository and credentials.

## Decision

Every setting lives in `microservices/config-repo/`, versioned with the code, and `config-service` serves it with Spring Cloud Config's **native** backend. `application.yaml` there applies to every service, `<service>.yaml` to one, and `*-k8s.yaml` files add Kubernetes overrides under the `k8s` profile. A service's own `application.yaml` holds only its name and the `configserver:` import. Secrets are placeholders with development defaults, overridden by environment variables.

## Consequences

- A configuration change is a reviewed commit, and one file shows everything a service is configured with.
- config-service must be up before any other service starts; locally it must be started from its module directory so `../config-repo` resolves, and on Kubernetes the directory is baked into its image.
- Development-only secret defaults are committed, which is convenient locally and unacceptable anywhere shared — see [known gaps](../../known-gaps/not-yet-built.md#security).
