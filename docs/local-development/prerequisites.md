# Prerequisites

| Tool | Version | Used for |
|---|---|---|
| JDK | 25 | Every service |
| Maven | — | Use the bundled wrapper (`mvnw`, or `mvnw.cmd` on Windows); don't install Maven separately. `microservices/` has its own wrapper |
| Docker | Recent, with Compose | PostgreSQL, Redis, Kafka and Control Center (`services.docker-compose.yaml`) |
| Python | 3.10+ | The [one-command demo and the dashboard](demo-and-dashboard.md), and the load-test, crash-test and spec-check scripts. Standard library only: nothing to `pip install` |
| A browser, curl or an HTTP client | — | The dashboard, or calling the API through the gateway directly |
| kind and kubectl | Recent | **Optional** — only for [running on Kubernetes](../deployment/README.md) |

No external accounts are needed. The bank, the card acquirer and the payout rail are all simulated inside the services, and every secret has a development-only default in `microservices/config-repo/`.

Next: [configuration](configuration.md).
