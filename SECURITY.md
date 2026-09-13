# Security Policy

## Reporting a vulnerability

Please **don't open a public issue** for a security problem. Report it privately through GitHub: on the repository's **Security** tab, choose **Report a vulnerability**.

Include what you found, how to reproduce it, and the impact you expect. You'll get an acknowledgement, and a fix or mitigation will be coordinated with you before any details are made public.

## Scope

This repository's code and deployment configuration. PayFlo has no hosted deployment; it runs locally and on a local Kubernetes cluster. Every card processor and bank in it is simulated — never put a real card number into it.

The committed secret values in `microservices/config-repo/` and `microservices/k8s/secrets.env.example` are development-only defaults, documented as such; they are not a vulnerability report in themselves.

## Security model

How PayFlo separates merchants, authenticates callers at the gateway, confines card data to the vault, and signs webhooks is described in the [security model](docs/architecture/security-model.md). The rules every change must respect are in the [security guardrails](docs/practices/security-guardrails.md), and the known open issues in [known gaps](docs/gaps.md).
