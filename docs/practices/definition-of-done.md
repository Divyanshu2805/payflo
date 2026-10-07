# Definition of Done

A change is done when every item that applies is true:

- [ ] **Every module builds and every test passes** — `./mvnw verify` in `microservices/` (needs Docker) — with no new compiler warnings (`@Builder.Default`, MapStruct unmapped properties). CI runs the same command.
- [ ] **A schema change has a Flyway migration** in the owning service, and the entity matches it: `contextLoads` builds the schema from the migrations on an empty database and validates the entities against it.
- [ ] **New logic has a test that would fail without it** — a unit test for money, security or state-machine logic, an integration test for anything that touches the database, Redis or Kafka.
- [ ] **The affected flow was verified by hand through the gateway** ([testing](testing.md#what-is-verified-by-hand)).
- [ ] **No remote call runs inside a transaction**, and every new Feign call has a circuit breaker, a retry and a `config-repo` instance.
- [ ] **Events go through the outbox**, and every new `@Scheduled` job has a ShedLock.
- [ ] **New configuration is in `config-repo`**, with a `-k8s` override and a ConfigMap or `secrets.env.example` entry if it differs in-cluster.
- [ ] **A new, removed or moved endpoint is in `docs/api/openapi.yaml`** — `python docs/api/check_openapi.py` passes (CI runs it) — and a changed request or response shape is changed there too.
- [ ] **A new path prefix has a gateway route** in both `api-gateway-service.yaml` and `api-gateway-service-k8s.yaml`.
- [ ] **No security guardrail was weakened** ([guardrails](security-guardrails.md)).
- [ ] **Every doc the change makes inaccurate is updated in the same commit** — architecture, API reference, data model, local development, deployment, `CLAUDE.md` — and any diagram it affects is regenerated from `docs/assets/diagrams/src/`.
