# Security Policy

## Reporting a vulnerability

Please **don't open a public issue** for a security problem. Report it privately through GitHub: on the repository's **Security** tab, choose **Report a vulnerability**.

Include what you found, how to reproduce it, and the impact you expect. You'll get an acknowledgement, and a fix or mitigation will be coordinated with you before any details are made public.

## Scope

This repository's code and deployment configuration. PayFlo has no hosted deployment; it runs locally and on a local Kubernetes cluster. Every card processor and bank in it is simulated — never put a real card number into it.

The committed secret values in `microservices/config-repo/` and `microservices/k8s/secrets.env.example` are development-only defaults, documented as such; they are not a vulnerability report in themselves.

## How the system protects data

- **Every request is authenticated once, at the gateway**, with a JWT or an API key. The gateway drops any identity header a client sends and writes its own.
- **A merchant reaches only its own data.** Another merchant's order, payment or key answers "not found".
- **Card numbers stay in vault-service**, encrypted with a key per card. Every other service sees a token, and the CVV is never stored.
- **Secrets are hashed or encrypted at rest**, and a secret is returned only in the response that creates it.
- **Webhooks are signed per attempt** with a timestamp, so a replayed request fails verification.
- **Sensitive actions are written to an append-only audit log** in the same transaction as the change.
- **Nothing but the gateway is reachable from outside**, and `/internal/**` needs a service token.

The full picture is in the [security model](docs/architecture/security-model.md), and the rules every change must respect are in the [security guardrails](docs/practices/security-guardrails.md).
