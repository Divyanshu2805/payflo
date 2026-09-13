# Definition of Done

A change is done when every item that applies is true:

- [ ] **It is in `microservices/`**, not the frozen monolith.
- [ ] **Every module builds** — `./mvnw clean install -DskipTests` in `microservices/` — with no new compiler warnings (`@Builder.Default`, MapStruct unmapped properties).
- [ ] **The touched services actually start** against config-service, and `contextLoads` passes for them.
- [ ] **The affected flow was verified by hand through the gateway** ([testing](testing.md#what-is-verified-by-hand)).
- [ ] **No remote call runs inside a transaction**, and every new Feign call has a circuit breaker, a retry and a `config-repo` instance.
- [ ] **Events go through the outbox**, and every new `@Scheduled` job has a ShedLock.
- [ ] **New configuration is in `config-repo`**, with a `-k8s` override and a ConfigMap or `secrets.env.example` entry if it differs in-cluster.
- [ ] **A new path prefix has a gateway route** in both `api-gateway-service.yaml` and `api-gateway-service-k8s.yaml`.
- [ ] **No security guardrail was weakened** ([guardrails](security-guardrails.md)).
- [ ] **Every doc the change makes inaccurate is updated in the same commit** — architecture, API reference, data model, local development, deployment, `CLAUDE.md` — and any diagram it affects is regenerated from `docs/assets/diagrams/src/`.
