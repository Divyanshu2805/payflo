# Security Model

PayFlo handles other businesses' money and their customers' card numbers. This page describes the boundaries that keep merchants apart, keep card data contained, and keep credentials safe, and where each one is enforced. The rules contributors must not break are summarised in [security guardrails](../practices.md); how to report a vulnerability is in [`SECURITY.md`](../../SECURITY.md).

## Two kinds of caller

| Caller | Credential | Issued by | Verified by |
|---|---|---|---|
| A merchant's staff, from a dashboard | `Authorization: Bearer <jwt>` — HMAC-signed, carries `merchant_id` and `role`, valid 100 minutes | `POST /v1/auth/login` (merchant-service), after a bcrypt password check | The gateway's `JwtAuthHandler`, with the shared `jwt.secret-key` |
| A merchant's own backend | `Authorization: Basic base64(keyId:secret)` — an API key, `fp_<environment>_<random>` | `POST /v1/merchants/api-keys`; the secret is shown once and stored only as a bcrypt hash | The gateway's `ApiKeyAuthHandler`: Redis cache, then merchant-service on a miss; the current secret, or the previous one during the 24-hour post-rotation grace period |

Both credentials resolve to the same thing — a merchant id — and every endpoint accepts either. See the [authentication flow](README.md) and [decision 0003](decisions/0003-authenticate-once-at-the-gateway.md).

## Authentication happens once, at the gateway

`GatewayAuthFilter` runs before routing on every request except `app.security.public-routes` (signup, login, `/webhook/**`, `/actuator/health`). No other service has a Spring Security filter chain. After a credential verifies, the gateway adds identity headers to the forwarded request:

- JWT: `X-Merchant-Id`, `X-User-Role`
- API key: `X-Merchant-Id`, `X-Key-Id`, `X-Environment`

Downstream, `common-lib`'s `MerchantContextFilter` reads `X-Merchant-Id` and `X-Key-Id` into the request-scoped `MerchantContext`, and controllers read the merchant only from there — never from a path, a query parameter or a body. The gateway itself runs with `app.security.trust-inbound-headers: false`, so its own `MerchantContextFilter` never trusts client-supplied headers.

Failures are answered by the gateway directly, in the same `{ errorCode, errorDescription }` shape as every service: `401 UNAUTHORIZED` for a missing, malformed or wrong credential, `429 RATE_LIMIT_EXCEEDED` with `Retry-After` over the per-key limit.

## Tenancy

There is one tenant boundary: the merchant. Every query that reads merchant data is scoped by the `merchantId` from `MerchantContext` — `findByIdAndMerchantId`, `findByIdAndMerchantIdForUpdate` — so another merchant's order or payment is simply not found (`404`), never forbidden. There is no role or permission distinction within a merchant: any authenticated user or API key can do anything that merchant can. See [known gaps](../gaps.md).

## Trusted identity headers

Business services believe `X-Merchant-Id` because only the gateway can reach them. That holds on Kubernetes, where every Service except the gateway's is `ClusterIP`, and locally only by convention. The gateway overwrites the identity headers it sets, but it does not strip the others a client may send — see [known gaps](../gaps.md) for what that leaves open.

## Internal API

`/internal/**` endpoints accept arbitrary merchant and payment ids and apply no scoping of their own; they trust that the calling service already resolved the merchant. They are protected only by reachability:

1. The gateway has no route for `/internal/**`.
2. On Kubernetes, business services are `ClusterIP` — unreachable from outside the cluster.

There is no service-to-service credential, mTLS or `NetworkPolicy` yet, so any pod in the namespace can call any internal endpoint.

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
| Webhook signing secrets | AES-encrypted with `webhook.secret-encryption-key` (`WEBHOOK_SECRET_KEY`); decrypted only to sign a delivery |
| Card numbers | Envelope-encrypted, above |

The JWT key, vault master key and webhook encryption key have **committed development defaults** in `config-repo/`, overridable by environment variable. On Kubernetes they come from the `app-secrets` Secret built from a gitignored `secrets.env`. A real deployment needs a secret store; see [known gaps](../gaps.md).

## Webhook signatures

Every outbound webhook carries an HMAC-SHA256 signature of its payload, computed with that webhook config's own secret, in the `X-PayFlo-Signature` header — so a merchant can verify a delivery came from PayFlo and wasn't altered.
