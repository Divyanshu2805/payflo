# 0003. Authenticate once, at the gateway

**Status:** Accepted

## Context

The monolith authenticated inside the application, with two Spring Security filter chains — JWT for dashboard routes, API key for merchant-backend routes. After the split, doing the same in every service would duplicate the credential logic four times, give every service the JWT key and a path to API-key hashes, and let them drift apart.

## Decision

All authentication happens in `api-gateway-service`. `GatewayAuthFilter` accepts either a Bearer JWT (verified with the shared `jwt.secret-key`) or a Basic API key (looked up in a Redis cache, falling back to merchant-service's internal API, and bcrypt-checked), applies the per-key rate limit, and forwards the caller's identity as `X-Merchant-Id`, `X-Key-Id`, `X-Environment` and `X-User-Role` headers. Business services have no security filter chain; `common-lib`'s `MerchantContextFilter` rebuilds `MerchantContext` from the headers, so controllers read the merchant exactly as they did in the monolith.

merchant-service still **issues** credentials (signup, login, API keys) but never verifies them.

## Consequences

- One place to change how callers are authenticated and throttled; the services stay free of credential handling.
- Every endpoint accepts either credential type — the monolith's split between JWT routes and API-key routes is gone.
- Business services trust the identity headers, which is safe only while nothing but the gateway can reach them. That holds on Kubernetes (every other Service is `ClusterIP`) but is not enforced by any credential or network policy — see [known gaps](../../gaps.md).
- The gateway needs Redis and merchant-service to authenticate API keys; a cache hit avoids the network call.
