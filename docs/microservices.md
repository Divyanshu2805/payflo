# Microservices (Phase 2)

[← Back to docs index](README.md)

_Last updated: 2026-01-03._

Phase 2 splits the frozen monolith into independently deployable Spring Boot services along the
domain boundaries it was built around (`common`, `merchant`, `payment`, `vault`, `operations`). The
split lives in [`microservices/`](../microservices) as a Maven multi-module build; the monolith at
the repo root is left untouched as the phase 1 reference.

Every service is its own Spring Boot application with its own `pom.xml`. `microservices/pom.xml` is
only an aggregator (no shared parent), so one command builds everything while each module stays
independently buildable and deployable.

## Module status

| Module | Port | Status |
|---|---|---|
| `common-lib` | — | Not started |
| `discovery-service` | 8761 | Not started |
| `config-service` | 8888 | Not started |
| `merchant-service` | 8081 | Not started |
| `vault-service` | 8083 | Not started |
| `payment-service` | 8082 | Not started |
| `operations-service` | 8084 | Not started |
| `api-gateway-service` | 8080 | Not started |

## Layout

```
microservices/
├── pom.xml          # aggregator — lists every module
├── mvnw, mvnw.cmd   # one Maven wrapper for the whole build
└── <module>/        # one directory per service / shared library
```
