# Local Development

Everything needed to run PayFlo on your own machine.

Local development runs **seven processes** — discovery, config, the four business services and the gateway — from the `microservices/` build. PostgreSQL, Redis and Kafka run in Docker.

**In a hurry?** `python demo/demo.py` from `microservices/` does all of the steps below, seeds a demo merchant and opens a dashboard: see [the demo and the dashboard](demo-and-dashboard.md).

| Step | Page |
|---|---|
| 1. Install the tools | [Prerequisites](prerequisites.md) |
| 2. Know where configuration lives | [Configuration](configuration.md) |
| 3. Start the stack and make a payment | [First-time setup](setup.md) |

## Reference

| Page | Covers |
|---|---|
| [The demo and the dashboard](demo-and-dashboard.md) | One command to start and seed everything, the web dashboard, Swagger UI |
| [Troubleshooting](troubleshooting.md) | Symptoms you're likely to hit, and their fixes |
| [Useful commands](commands.md) | Build, run, test and infrastructure commands in one place |
| [Resetting local data](resetting-data.md) | Starting over from empty databases, cache and topics |

To run the same services on Kubernetes instead, see [Deployment](../deployment/README.md). Tests are covered in [Testing](../practices/testing.md).
