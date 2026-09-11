# Local Development

Everything needed to run PayFlo on your own machine.

Local development runs **seven processes** — discovery, config, the four business services and the gateway — from the `microservices/` build. PostgreSQL, Redis and Kafka run in Docker. The frozen phase 1 monolith at the repository root can still be run on its own for reference.

| Step | Page |
|---|---|
| 1. Install the tools | [Prerequisites](prerequisites.md) |
| 2. Know where configuration lives | [Configuration](configuration.md) |
| 3. Start the stack and make a payment | [First-time setup](setup.md) |

## Reference

| Page | Covers |
|---|---|
| [Troubleshooting](troubleshooting.md) | Symptoms you're likely to hit, and their fixes |
| [Useful commands](commands.md) | Build, run, test and infrastructure commands in one place |
| [Resetting local data](resetting-data.md) | Starting over from empty databases, cache and topics |
| [The monolith](the-monolith.md) | Building, running and testing the frozen phase 1 application |

To run the same services on Kubernetes instead, see [Deployment](../deployment.md). Tests are covered in [Testing](../practices.md).
