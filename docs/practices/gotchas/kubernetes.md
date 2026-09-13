# Pitfalls: Kubernetes

## config-service needs `native,k8s`, not just `k8s`

- **Symptom:** config-service crash-loops on the cluster, looking for a Git URI.
- **Cause:** the shared `app-config` ConfigMap sets `SPRING_PROFILES_ACTIVE=k8s`. For config-service that *replaces* the `native` profile, so the config server falls back to its default Git backend.
- **Fix:** config-service's Deployment sets `SPRING_PROFILES_ACTIVE=native,k8s` explicitly, overriding the ConfigMap.

## Services crash-loop until config-service answers

- **Symptom:** on a fresh deploy, business-service pods restart a few times before becoming ready.
- **Cause:** each service fails fast when it can't import its configuration (`SPRING_CLOUD_CONFIG_FAIL_FAST=true`, with 20 retries) and config-service takes a while to start.
- **Fix:** nothing — it's expected. The start-up probe allows up to 5 minutes; everything settles in a few.

## Eureka is off in-cluster, so Feign needs explicit URLs

- **Symptom:** a Feign call fails with "no instances available" on the cluster, though it works locally.
- **Cause:** the `k8s` profile disables Eureka; `lb://merchant-service` has nothing to resolve against.
- **Fix:** every Feign client takes an optional base URL (`url = "${MERCHANT_SERVICE_URI:}"`); the ConfigMap sets `MERCHANT_SERVICE_URI`, `PAYMENT_SERVICE_URI`, `VAULT_SERVICE_URI` to the Kubernetes Services. A new client needs the same pattern and a ConfigMap entry. The gateway's routes have their own `-k8s` file.

## Jib gets `401 Unauthorized` pulling the base image

- **Symptom:** `jib:dockerBuild` fails pulling `eclipse-temurin:25-jre` from Docker Hub.
- **Cause:** stale Docker Hub credentials in the local Docker config are sent with an anonymous pull.
- **Fix:** `docker pull eclipse-temurin:25-jre` once, then build with `-Djib.from.image=docker://eclipse-temurin:25-jre` to use the local copy.

## kind can't find images that exist locally

- **Symptom:** pods stay in `ErrImagePull` / `ImagePullBackOff` for `payflo/<module>:latest`.
- **Cause:** kind's node has its own image store; images in the local Docker daemon aren't visible to it.
- **Fix:** `kind load docker-image payflo/<module>:latest --name payflo` after every build. The Deployments use `imagePullPolicy: IfNotPresent`, so a loaded image is used as-is — reload after rebuilding.

## Port 8080 is already taken on the host

- **Symptom:** `kind create cluster` fails to bind the host port.
- **Cause:** `kind-config.yaml` maps the gateway's NodePort `30080` to `localhost:8080`.
- **Fix:** change `hostPort` in `kind-config.yaml`, or stop whatever holds 8080 (a local gateway or the monolith).
