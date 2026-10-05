# Security Model

PayFlo handles other businesses' money and their customers' card numbers. This page describes the boundaries that keep merchants apart, keep card data contained, and keep credentials safe, and where each one is enforced. The rules contributors must not break are summarised in [security guardrails](../practices/security-guardrails.md); how to report a vulnerability is in [`SECURITY.md`](../../SECURITY.md).

## Two kinds of caller

| Caller | Credential | Issued by | Verified by |
|---|---|---|---|
| A merchant's staff, from a dashboard | `Authorization: Bearer <jwt>` — HMAC-signed, carries `merchant_id` and `role`, valid 100 minutes | `POST /v1/auth/login` (merchant-service), after a bcrypt password check | The gateway's `JwtAuthHandler`, with the shared `jwt.secret-key` |
| A merchant's own backend | `Authorization: Basic base64(keyId:secret)` — an API key, `pf_<environment>_<random>` | `POST /v1/merchants/api-keys`; the secret is shown once and stored only as a bcrypt hash | The gateway's `ApiKeyAuthHandler`: Redis cache, then merchant-service on a miss; the current secret, or the previous one during the 24-hour post-rotation grace period |

Both credentials resolve to the same thing — a merchant id — and every endpoint accepts either. See the [authentication flow](flows/authentication.md) and [decision 0003](decisions/0003-authenticate-once-at-the-gateway.md).

## Authentication happens once, at the gateway

`GatewayAuthFilter` runs before routing on every request except `app.security.public-routes` (signup, login, `/webhook/**`). The gateway's own Actuator endpoints are on a separate management port (`9081`) that this filter — and, on Kubernetes, the public NodePort — doesn't cover. No other service has a Spring Security filter chain. After a credential verifies, the gateway adds identity headers to the forwarded request:

- JWT: `X-Merchant-Id`, `X-User-Role`
- API key: `X-Merchant-Id`, `X-Key-Id`, `X-Environment`

Downstream, `common-lib`'s `MerchantContextFilter` reads `X-Merchant-Id` and `X-Key-Id` into the request-scoped `MerchantContext`, and controllers read the merchant only from there — never from a path, a query parameter or a body. The gateway itself runs with `app.security.trust-inbound-headers: false`, so its own `MerchantContextFilter` never trusts client-supplied headers.

Failures are answered by the gateway directly, in the same `{ errorCode, errorDescription }` shape as every service: `401 UNAUTHORIZED` for a missing, malformed or wrong credential, `403 MERCHANT_SUSPENDED` for a valid credential belonging to a suspended merchant, and `429 RATE_LIMIT_EXCEEDED` with `Retry-After` over a limit.

The gateway also bounds guessing and abuse of the credentials themselves:

- **Failed attempts are counted per client address** (30 a minute by default); past that, the address is refused before any credential is checked, so a wrong key can't be tried at bcrypt speed. A key id that doesn't look like `pf_<env>_<random>` is refused without any lookup, and an id that doesn't exist is remembered for a minute so repeated guesses don't each reach the database.
- **Signup and login are limited per client address**, JWT traffic per merchant, API-key traffic per key. Settings and defaults are in [idempotency and rate limits](../api/idempotency-and-rate-limits.md#rate-limits). Behind a proxy set `CLIENT_IP_HEADER`, or every client shares the proxy's address.
- **A suspended merchant is refused whatever it presents** — an API key, or a JWT issued before the suspension. The status is looked up from merchant-service and cached for 60 seconds, so a suspension takes effect within a minute; if the lookup fails the request is let through, so a merchant-service outage doesn't stop everyone paying.
- **Login locks after 10 wrong passwords** for an email within 15 minutes (`429`), counted whether or not the email is registered, and an unknown email costs the same bcrypt check as a wrong password.

## Tenancy

There is one tenant boundary: the merchant. Every query that reads merchant data is scoped by the `merchantId` from `MerchantContext` — `findByIdAndMerchantId`, `findByIdAndMerchantIdForUpdate` — so another merchant's order or payment is simply not found (`404`), never forbidden. There is no role or permission distinction within a merchant: any authenticated user or API key can do anything that merchant can. See [known gaps](../known-gaps/not-yet-built.md#security).

## Trusted identity headers

Business services believe `X-Merchant-Id` because only the gateway can reach them. That holds on Kubernetes, where every Service except the gateway's is `ClusterIP`, and locally only by convention. The gateway drops `X-Merchant-Id`, `X-Key-Id`, `X-User-Role` and `X-Environment` from every inbound request — on public routes too — before adding the ones it has verified, so a client can never get an identity header of its own through (`HeaderAugmentingRequestWrapper`).

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
| Webhook signing secrets | AES-encrypted with `webhook.secret-encryption-key` (`WEBHOOK_SECRET_KEY`); decrypted only to sign a delivery. operations-service keeps the decrypted targets in memory for up to 30 s (`WebhookTargetCache`), never in Redis or its database |
| Card numbers | Envelope-encrypted, above |

The JWT key, vault master key, webhook encryption key and internal token have **committed development defaults** in `config-repo/`, overridable by environment variable. On Kubernetes they come from the `app-secrets` Secret built from a gitignored `secrets.env`. A real deployment needs a secret store; see [known gaps](../known-gaps/not-yet-built.md#security).

A service checks the secrets it holds at start-up (`SecretConfigurationChecker`). With `ENFORCE_STRONG_SECRETS=true` (`app.security.enforce-strong-secrets`) it **refuses to start** on a committed default, a JWT key or token that is too short, or a key that isn't 32 base64 bytes; with it off (the default) it logs a warning naming the secret. Set it in any shared environment, and give the vault master key and the webhook encryption key different values — both default to the same one. There is no key versioning: changing a key makes everything encrypted under the old one unreadable until it is re-encrypted.

## Transport and Redis

Traffic is plain HTTP between the services and, by default, to the gateway. The gateway can serve TLS itself (`GATEWAY_TLS_ENABLED`, `GATEWAY_TLS_KEYSTORE`, `GATEWAY_TLS_KEYSTORE_PASSWORD`), but more often TLS is terminated by a load balancer or Ingress in front of it. **Inside the platform nothing is encrypted:** a card number travels from the merchant to the gateway and on to vault-service in clear unless the front is TLS *and* the hop between pods is protected by a service mesh or mTLS, which isn't set up. Redis, which holds the API-key cache and idempotency responses, accepts a password (`REDIS_PASSWORD`; empty means none, the development default) but not TLS.

## Webhook targets

A webhook URL is merchant-supplied and operations-service calls it from inside the platform, so it is validated (`common-lib`'s `WebhookUrlValidator`) when the config is saved and again just before each delivery: `https`, no embedded credentials, and every address the host resolves to must be public. Without that a merchant could aim a webhook at `localhost`, a cluster Service such as `vault-service`, or a cloud metadata address. Link-local addresses are always refused; loopback, private ranges and plain `http` are allowed only while `WEBHOOK_ALLOW_PRIVATE_TARGETS` is `true`, which is the development default and must be `false` in a shared environment. Redirects aren't followed (POSTs aren't by the JDK client). The delivery-time check narrows, but doesn't close, a DNS-rebinding window — the connection isn't pinned to the address that was checked.

## Webhook signatures

Every outbound webhook carries an HMAC-SHA256 signature of its payload, computed with that webhook config's own secret, in the `X-PayFlo-Signature` header — so a merchant can verify a delivery came from PayFlo and wasn't altered.
