# Security Model

PayFlo handles other businesses' money and their customers' card numbers. This page describes the boundaries that keep merchants apart, keep card data contained, and keep credentials safe, and where each one is enforced. The rules contributors must not break are summarised in [security guardrails](../practices/security-guardrails.md); how to report a vulnerability is in [`SECURITY.md`](../../SECURITY.md).

## Two kinds of caller

| Caller | Credential | Issued by | Verified by |
|---|---|---|---|
| A merchant's staff, from a dashboard | `Authorization: Bearer <jwt>` — HMAC-signed, carries `merchant_id` and `role`, valid 100 minutes | `POST /v1/auth/login` (merchant-service), after a bcrypt password check | The gateway's `JwtAuthHandler`, with the shared `jwt.secret-key` |
| A merchant's own backend | `Authorization: Basic base64(keyId:secret)` — an API key, `pf_<environment>_<random>` | `POST /v1/merchants/api-keys`; the secret is shown once and stored only as a bcrypt hash | The gateway's `ApiKeyAuthHandler`: Redis cache, then merchant-service on a miss; the current secret, or the previous one during the 24-hour post-rotation grace period |

Both credentials resolve to the same thing — a merchant id — and every endpoint accepts either. See the [authentication flow](flows/authentication.md) and [decision 0003](decisions/0003-authenticate-once-at-the-gateway.md).

There is a third caller that is not a merchant at all: **the platform operator**, who uses the [admin API](../api/admin.md) with a separate key. It is described [below](#the-platform-operator-the-admin-api).

## Authentication happens once, at the gateway

`GatewayAuthFilter` runs before routing on every request except `app.security.public-routes` (signup, login, `/webhook/**`). The gateway's own Actuator endpoints are on a separate management port (`9081`) that this filter — and, on Kubernetes, the public NodePort — doesn't cover. No other service has a Spring Security filter chain. After a credential verifies, the gateway adds identity headers to the forwarded request:

- JWT: `X-Merchant-Id`, `X-User-Role`
- API key: `X-Merchant-Id`, `X-Key-Id`, `X-Environment`

Downstream, `common-lib`'s `MerchantContextFilter` reads `X-Merchant-Id` and `X-Key-Id` into the request-scoped `MerchantContext`, and controllers read the merchant only from there — never from a path, a query parameter or a body. The gateway itself runs with `app.security.trust-inbound-headers: false`, so its own `MerchantContextFilter` never trusts client-supplied headers.

Failures are answered by the gateway directly, in the same `{ errorCode, errorDescription }` shape as every service: `401 UNAUTHORIZED` for a missing, malformed or wrong credential, `403 MERCHANT_SUSPENDED` for a valid credential belonging to a suspended merchant, and `429 RATE_LIMIT_EXCEEDED` with `Retry-After` over a limit.

The gateway also bounds guessing and abuse of the credentials themselves:

- **Failed attempts are counted per client address** (30 a minute by default); past that, the address is refused before any credential is checked, so a wrong key can't be tried at bcrypt speed. A key id that doesn't look like `pf_<env>_<random>` is refused without any lookup, and an id that doesn't exist is remembered for a minute so repeated guesses don't each reach the database.
- **Signup and login are limited per client address**, JWT traffic per merchant, API-key traffic per key. Settings and defaults are in [idempotency and rate limits](../api/idempotency-and-rate-limits.md#rate-limits). Behind a proxy set `CLIENT_IP_HEADER`, or every client shares the proxy's address.
- **A suspended merchant is refused whatever it presents** — an API key, or a JWT issued before the suspension. The status is looked up from merchant-service and cached for 60 seconds, so a suspension made any other way takes effect within a minute; the [admin API's](../api/admin.md) suspend and reactivate write that cache entry themselves once they have committed, so they apply at once. If the lookup fails the request is let through, so a merchant-service outage doesn't stop everyone paying.
- **Login locks after 10 wrong passwords** for an email within 15 minutes (`429`), counted whether or not the email is registered, and an unknown email costs the same bcrypt check as a wrong password.

## Card testing

A valid API key is also a tool for abuse: feed it a list of stolen card numbers, tokenize each and charge a little, and the ones that go through are live. The gateway's rate limits don't see this (a few hundred requests a minute is well within them), so [three velocity rules](../api/idempotency-and-rate-limits.md#velocity-limits-and-card-testing) sit where the card is handled: vault-service refuses a merchant tokenizing cards faster than any checkout (`CARD_TOKENIZATION_LIMIT_EXCEEDED`); payment-service refuses a merchant whose recent card payments are mostly declines (`CARD_TESTING_SUSPECTED`) and an order that has had five (`ORDER_CARD_ATTEMPTS_EXCEEDED`). The counts are in Redis (one hash per merchant and window, so attempts and failures cover the same time), fail open if Redis is down, and the refusals are counted in `payflo.velocity.refused`. Declines caused by PayFlo's own outage don't count against a card. The rules look at the merchant, not at the card, the customer or the client address, so they stop the usual shape of the abuse rather than every shape of it.

## Roles and sessions

A merchant's dashboard users have a role in the JWT. **`OWNER`** (the one who signed up; there is only one) can do everything, **`ADMIN`** everything except the payout account, KYC and managing users, and **`TEAM`** can only read. Two layers enforce it: the gateway refuses any non-`GET` request from a `TEAM` login (`403 ROLE_FORBIDDEN`), and merchant-service's `CallerPolicy` checks the dashboard-only actions — profile, payout account, KYC, users, password. Those need a *person*: an API key, which is a merchant's backend credential and could be a leaked secret, has no say in where the money goes (`403 DASHBOARD_LOGIN_REQUIRED`). Changing the payout account also needs the password again. The role and the user's email travel to the services in `X-User-Role` and `X-User-Email`, written by the gateway like the other identity headers.

Access tokens live 100 minutes and carry their own id. Merchant-service keeps **refresh tokens** (random, single use, stored only as a hash, 7 days) and **revocation** state in Redis, which the gateway reads on every JWT request: a logged-out token is refused by its id, and a password change, role change or removal refuses everything issued to that user before that moment. See [authentication](../api/authentication.md#sessions).

A merchant's payout account number is stored AES-GCM-encrypted (the same key as webhook secrets) and only ever returned masked.

## Tenancy

There is one tenant boundary: the merchant. Every query that reads merchant data is scoped by the `merchantId` from `MerchantContext` — `findByIdAndMerchantId`, `findByIdAndMerchantIdForUpdate` — so another merchant's order or payment is simply not found (`404`), never forbidden. Within a merchant, what a caller may do depends on its [role](#roles-and-sessions); an API key can do everything except the dashboard-only actions (profile, payout account, KYC, users, password).

## The platform operator: the admin API

Someone has to be able to suspend a merchant, run a settlement and read the audit log without editing a database, and that someone is not a merchant. The `/v1/admin/**` endpoints take **one key, `X-Admin-Key`** (`app.security.admin-api-key`, from `ADMIN_API_KEY`), checked by the gateway's `AdminAuthHandler` and nowhere else:

- **On an admin path the gateway looks at the admin key and nothing else.** A merchant's JWT or API key isn't tried, so no credential a merchant can create, rotate or leak opens it; and the admin key opens nothing outside `/v1/admin/`.
- The key is compared as digests in constant time, and a wrong one counts as a failed authentication against the caller's address (blocked after 30 a minute, like any guessing). With no key configured the admin API is off (`403 ADMIN_API_DISABLED`), and `ENFORCE_STRONG_SECRETS` refuses the committed development default, as it does for the JWT key and the internal token.
- After it checks the key the gateway sets `X-Platform-Admin: true`. Services refuse an admin request without it (`PlatformAdminFilter`, `403 ADMIN_REQUIRED`), which stops a request that reaches a service by some other route from being taken for the operator's — with the same limit as `X-Merchant-Id`: a caller who can reach a service directly and write headers can claim anything, which is why services are not published beyond the gateway.
- **Every admin action is audited, in the same transaction as the change** — or, for the settlement run in operations-service, *before* it starts: if the audit log can't be written, nothing runs. See [the audit log](../api/audit-log.md).
- The operator sees less than a merchant does: no payout account, PAN or GSTIN, and nothing in the admin API reads a card, a secret or a payment's details.

The key is one shared secret, not a login per operator, so the audit log can say *the operator* did it but not *which* one, and rotating it means restarting the gateway. A real deployment would put the admin API behind SSO and per-person identity.

## Trusted identity headers

Business services believe `X-Merchant-Id` because only the gateway can reach them. That holds on Kubernetes, where every Service except the gateway's is `ClusterIP`, and locally only by convention. The gateway drops `X-Merchant-Id`, `X-Key-Id`, `X-User-Role`, `X-User-Email`, `X-Environment`, `X-Platform-Admin` and `X-Client-Ip` from every inbound request — on public routes too — before adding the ones it has verified, so a client can never get an identity header of its own through (`HeaderAugmentingRequestWrapper`). `X-Client-Ip` is the caller's address as the gateway saw it (or from `CLIENT_IP_HEADER` behind a proxy); services only record it, in the audit log, and never decide anything from it.

## Internal API

`/internal/**` endpoints accept arbitrary merchant and payment ids and apply no scoping of their own; they trust that the calling service already resolved the merchant. They are protected by three things:

1. The gateway has no route for `/internal/**`.
2. On Kubernetes, business services are `ClusterIP` — unreachable from outside the cluster.
3. **A shared service token.** Every service rejects an `/internal/**` request without the right `X-Internal-Token` (`INTERNAL_API_TOKEN`, compared in constant time by `InternalApiAuthFilter`), and every Feign client sends it. A pod that can reach a service on the network still can't call an internal endpoint without it.

The token is one secret for the whole platform, not an identity per service: whoever holds it can call any internal endpoint, including the one that returns decrypted webhook signing secrets, and rotating it means restarting every service. There is still no mTLS or `NetworkPolicy`. A blank token stops the service starting.

## The card vault

Card data is confined to vault-service and its database — see [decision 0004](decisions/0004-isolate-card-data-in-vault-service.md).

- **Envelope encryption.** Each tokenized card gets its own random AES-256 data key (DEK). The PAN is encrypted with it (AES-GCM), and the DEK is itself encrypted with the master key (`vault.master-key`, from `VAULT_MASTER_KEY`). The database holds only the encrypted PAN and the wrapped DEK, so a database dump alone recovers nothing.
- **Only vault-service holds the master key.** `common-lib` creates the master-key encryptor only in a service that configures a key, and on Kubernetes `VAULT_MASTER_KEY` is injected into the vault-service pod alone.
- **The PAN never leaves.** Merchants get back a token, brand, last four and expiry. payment-service charges a card by sending the token to `POST /internal/vault/charge`; vault-service decrypts it, runs the acquirer call, and returns only the outcome.
- **CVV is validated, never stored.**

## Secrets at rest

| Secret | Stored as |
|---|---|
| Dashboard passwords | bcrypt hash (`app_user.password_hash`) |
| API-key secrets | bcrypt hash, plus the previous hash during rotation |
| Webhook signing secrets | AES-encrypted with `webhook.secret-encryption-key` (`WEBHOOK_SECRET_KEY`); decrypted only to sign a delivery. operations-service keeps the decrypted targets and secrets in memory for up to 30 s (`WebhookTargetCache`, `WebhookSecretResolver`), never in Redis or its database |
| Card numbers | Envelope-encrypted, above |

The JWT key, vault master key, webhook encryption key and internal token have **committed development defaults** in `config-repo/`, overridable by environment variable. On Kubernetes they come from the `app-secrets` Secret built from a gitignored `secrets.env`. A real deployment needs a secret store; see [design trade-offs](trade-offs.md#secrets-have-development-defaults).

A service checks the secrets it holds at start-up (`SecretConfigurationChecker`). With `ENFORCE_STRONG_SECRETS=true` (`app.security.enforce-strong-secrets`) it **refuses to start** on a committed default, a JWT key or token that is too short, or a key that isn't 32 base64 bytes; with it off (the default) it logs a warning naming the secret. Set it in any shared environment, and give the vault master key and the webhook encryption key different values — both default to the same one. There is no key versioning: changing a key makes everything encrypted under the old one unreadable until it is re-encrypted.

## Network exposure

The trust model above only holds if nothing but the gateway can reach a business service, so reachability is closed at every level:

- **Locally**, every service listens on `127.0.0.1` (`server.address: ${SERVER_ADDRESS:127.0.0.1}`) and registers that address with Eureka, and the infrastructure containers (PostgreSQL, Redis, Kafka, Control Center, Zipkin, Prometheus, Grafana) publish their ports on `127.0.0.1` only. Another machine on the same network can reach none of them: before this, a service bound to every interface would accept a request carrying a forged `X-Merchant-Id` from anyone on the Wi-Fi. To demo to another machine, set `SERVER_ADDRESS=0.0.0.0` on the gateway alone.
- **On Kubernetes**, the ConfigMap sets `SERVER_ADDRESS=0.0.0.0` inside the pod (a pod's address is its own), and only the gateway has a NodePort; every other Service is `ClusterIP`.
- **The tools that show or change data are not open.** Grafana has no anonymous access and no `admin`/`admin` (its password is `GRAFANA_ADMIN_PASSWORD`); the Kafka UI on Kubernetes needs a login (`KAFKA_UI_PASSWORD`); Control Center, which has no login of its own, is reachable only from this machine.

## What the gateway does to every response and request

- **Security headers on every response**, its own and relayed (`SecurityHeadersFilter`): `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store`, and `Strict-Transport-Security` when the connection is TLS. This is a JSON API, never a page, so the policy is as strict as a browser allows.
- **A request body is capped** at 1 MB (`app.security.max-request-body-bytes`, `MAX_REQUEST_BODY_BYTES`) and a header block at 16 KB. `RequestSizeLimitFilter` runs before authentication, refuses a declared oversize body with `413 REQUEST_TOO_LARGE` without reading it, and cuts off a streamed one the moment it passes the limit, so an oversized request costs almost nothing.

## Dependencies and the build

Continuous integration fails on a fixable HIGH or CRITICAL vulnerability in any dependency and on a committed secret; see [testing](../practices/testing.md#continuous-integration). The scan found twelve vulnerable packages on the first run (Tomcat, Spring MVC, both generations of Jackson, Netty, BouncyCastle, the PostgreSQL driver, FreeMarker, HttpComponents): most were cleared by moving to Spring Boot 4.1.1 and Spring Cloud 2025.1.3, and Jackson, Tomcat and FreeMarker are pinned to their fixed releases in each module's `pom.xml` until Boot's own versions catch up.

## Transport and Redis

Traffic is plain HTTP between the services and, by default, to the gateway. The gateway can serve TLS itself (`GATEWAY_TLS_ENABLED`, `GATEWAY_TLS_KEYSTORE`, `GATEWAY_TLS_KEYSTORE_PASSWORD`), but more often TLS is terminated by a load balancer or Ingress in front of it. **Inside the platform nothing is encrypted:** a card number travels from the merchant to the gateway and on to vault-service in clear unless the front is TLS *and* the hop between pods is protected by a service mesh or mTLS, which isn't set up. Redis, which holds the API-key cache and idempotency responses, accepts a password (`REDIS_PASSWORD`; empty means none, the development default) but not TLS.

## Webhook targets

A webhook URL is merchant-supplied and operations-service calls it from inside the platform, so it is validated (`common-lib`'s `WebhookUrlValidator`) when the config is saved and again just before each delivery: `https`, no embedded credentials, and every address the host resolves to must be public. Without that a merchant could aim a webhook at `localhost`, a cluster Service such as `vault-service`, or a cloud metadata address. Link-local addresses are always refused; loopback, private ranges and plain `http` are allowed only while `WEBHOOK_ALLOW_PRIVATE_TARGETS` is `true`, which is the development default and must be `false` in a shared environment. Redirects aren't followed (POSTs aren't by the JDK client). The delivery-time check narrows, but doesn't close, a DNS-rebinding window — the connection isn't pinned to the address that was checked.

## Webhook signatures

Every outbound webhook attempt carries an HMAC-SHA256 signature, in `X-PayFlo-Signature`, over `<timestamp>.<raw body>` (the exact bytes sent), computed with that webhook config's own secret, and the timestamp itself in `X-PayFlo-Timestamp` — so a merchant can verify a delivery came from PayFlo, wasn't altered, and is recent. The timestamp is taken when the attempt is sent and is part of what is signed, so a captured request can't be replayed later with a fresher one, and a receiver that refuses timestamps outside its tolerance (5 minutes is typical) refuses a replay. A retry is a new attempt with a new timestamp and signature, so late retries still verify. A secret can be rotated (`POST …/rotate-secret`) and takes effect for the next attempt; the body carries a stable event `id` so receivers can also de-duplicate. The signing secret is fetched from merchant-service for each attempt and never stored in operations-service's database. See [the verification recipe](../api/webhook-configs.md).
