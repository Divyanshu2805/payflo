# Known Pitfalls

Traps this stack has actually hit. Most fail **silently** — no compile error, no startup warning, just wrong behaviour — which is why they're written down. Each entry gives the symptom, the cause and the fix.

| Page | Covers |
|---|---|
| [Spring and JPA](spring-and-jpa.md) | Lombok builders, MapStruct, time zones, Spring Security auto-configuration, exception resolution |
| [Microservices](microservices.md) | Feign and sealed types, `common-lib` beans and jars, config-service, transactions around remote calls, Kafka topics, scheduling |
| [Kubernetes](kubernetes.md) | Profiles, Jib images, kind, start-up order, Eureka in-cluster |

A recurring lesson across all three: **a green build is not proof the behaviour is right.** A field silently left `null`, a filter chain that swallowed an exception, a type Jackson couldn't pick — each compiled and started cleanly here. Verify the effect through the gateway, not just the exit code.
