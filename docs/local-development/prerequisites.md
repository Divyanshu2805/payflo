# Prerequisites

| Tool | Version | Used for |
|---|---|---|
| JDK | 25 | Every service |
| Maven | — | Use the bundled wrapper (`mvnw`, or `mvnw.cmd` on Windows); don't install Maven separately. `microservices/` has its own wrapper |
| Docker | Recent, with Compose | PostgreSQL, Redis, Kafka and Control Center (`services.docker-compose.yaml`) |
| curl or an HTTP client | — | Calling the API through the gateway — there is no frontend |
| kind and kubectl | Recent | **Optional** — only for [running on Kubernetes](../deployment.md) |

No external accounts are needed. The bank, the card acquirer and the payout rail are all simulated inside the services, and every secret has a development-only default in `microservices/config-repo/`.

Next: [configuration](configuration.md).
