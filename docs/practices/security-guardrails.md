# Security Guardrails

Rules no change may break. Each one protects a boundary described in the [security model](../architecture/security-model.md); if a feature seems to need one of them relaxed, raise it for discussion instead of working around it.

1. **Card numbers never leave vault-service.** Only vault-service decrypts a card, and it returns outcomes, never numbers. Nothing else stores, logs or transmits a PAN. The CVV is never stored anywhere.
2. **Only vault-service holds the master key.** Don't configure `vault.master-key` in another service, and on Kubernetes don't inject `VAULT_MASTER_KEY` into another pod.
3. **Authentication stays at the gateway.** Business services trust `X-Merchant-Id` only because the gateway sets it; don't add a second path into a service that bypasses the gateway, and don't expose a business service outside the cluster.
4. **The merchant comes only from `MerchantContext`.** Every query over merchant data is scoped by it. An endpoint that takes a merchant id from the request would let one merchant act as another.
5. **`/internal/**` is never routed by the gateway.** Don't add a gateway route under `/internal`, and don't make an internal endpoint public.
6. **Secrets are hashed or encrypted at rest.** Passwords and API-key secrets are bcrypt hashes; webhook signing secrets are AES-encrypted; a secret is returned only in the response that creates it.
7. **Public routes stay minimal.** `app.security.public-routes` is signup, login, the test webhook endpoint and health. Adding to it removes authentication from that path entirely.
8. **Webhooks are always signed.** Every outbound delivery carries `X-PayFlo-Signature`, computed with that config's own secret.
9. **Payment status changes go through the state machine**, so every move is validated and logged.
10. **Development secret defaults stay development-only.** Never reuse them in a shared environment, and never commit a real value — use `secrets.env`, which is gitignored.
