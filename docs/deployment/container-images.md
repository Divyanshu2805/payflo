# Container Images

Six images, one per deployable module, built by [Jib](https://github.com/GoogleContainerTools/jib) from each module's `pom.xml` — there are no Dockerfiles.

| Image | Module | Notes |
|---|---|---|
| `payflo/config-service` | config-service | Also bakes `microservices/config-repo/` into the image at `/config-repo`, which the `k8s` profile reads |
| `payflo/merchant-service`, `payflo/payment-service`, `payflo/vault-service`, `payflo/operations-service` | the business services | |
| `payflo/api-gateway-service` | api-gateway-service | |

`common-lib` is a library, not an image, and `discovery-service` isn't built as one because Eureka isn't used in-cluster.

## How each image is built

- **Base image** `eclipse-temurin:25-jre`.
- **JVM sized from the container limit** with `-XX:MaxRAMPercentage=75`.
- **Tags** `${image.prefix}/<module>:<version>` and `latest`; `image.prefix` defaults to `payflo`.
- **Not bound to a lifecycle phase** — a normal `mvnw package` never builds or pushes an image. Build explicitly:

```bash
./mvnw -DskipTests jib:dockerBuild -pl <module>                                # into the local Docker daemon
```

```bash
./mvnw -DskipTests jib:build -pl <module> -Dimage.prefix=<registry-user>       # push to a registry
```

Jib layers dependencies separately from application classes, so rebuilding after a code change only replaces the small top layer.
