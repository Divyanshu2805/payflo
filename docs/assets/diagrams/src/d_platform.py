"""
Platform diagrams: service-to-service communication and the Kubernetes (kind) deployment topology.

Handles: every Feign client and the /internal endpoint it calls, the outbox → Kafka event paths, the Resilience4j
wrapping, and the manifests under microservices/k8s — namespace, workloads, Services, ConfigMap, Secret and volumes.
"""
from kit import Scene, C, LINE, MUTED

G, B, P, O, Y, R, T, GR = C["green"], C["blue"], C["purple"], C["orange"], C["yellow"], C["red"], C["teal"], C["gray"]


def service_communication():
    s = Scene(1700, 1040, "Service-to-service communication",
              "Synchronous calls are Feign → /internal/** (never routed by the gateway); everything else is an outbox event on Kafka")
    s.group(40, 110, 1620, 860, "Microservices", G, "box", sub="Feign clients resolve peers by name through Eureka")

    gw = s.node(80, 160, "api-gateway-service", "spring", "authenticates every public call", layout="row", w=300,
                accent=O)
    ms = s.node(650, 150, "merchant-service", "springboot", "owns merchants, API keys, webhook configs", w=320, h=92,
                accent=G)
    ps = s.node(120, 600, "payment-service", "springboot", "owns orders and payments", w=300, h=92, accent=G)
    os_ = s.node(1180, 600, "operations-service", "springboot", "owns webhooks and settlement", w=300, h=92, accent=G)
    vs = s.node(120, 850, "vault-service", "springboot", "owns vaulted cards", w=300, h=92, accent=G)
    kf = s.node(660, 620, "Kafka", "stream", "payments · orders · settlements", layout="row", w=300, accent=Y, icolor=Y)

    s.edge([gw.r(), (ms.x, gw.cy)], None, P)
    s.listbox(80, 240, "gateway → merchant · ApiKeyLookupClient", ["GET   /internal/api-keys/{keyId}"], P)
    s.pill(510, gw.cy, "Redis cache miss", P)

    s.edge([ps.r(-30), (520, ps.cy - 30), (520, ms.cy + 20), ms.l(20)], None, P)
    s.listbox(80, 400, "payment → merchant · CustomerServiceClient", ["POST  /internal/customers/find-or-create"], P)

    s.edge([os_.t(-60), (os_.cx - 60, ms.cy + 20), ms.r(20)], None, P)
    s.listbox(640, 330, "operations → merchant · MerchantServiceClient", [
        "GET   /internal/merchants/{id}/webhook-targets",
        "GET   /internal/merchants/active-ids",
        "GET   /internal/merchants/{id}/settlement-bank-details"], P)

    s.edge([ps.b(), vs.t()], None, P)
    s.listbox(460, 880, "payment → vault · VaultServiceClient", ["POST  /internal/vault/charge",
                                                             "      token + amount, never a PAN"], P)

    s.edge([os_.b(), (os_.cx, 790), (ps.cx + 110, 790), ps.b(110)], None, P)
    s.listbox(840, 820, "operations → payment · PaymentServiceClient", [
        "GET   /internal/payments/unsettled-captured",
        "POST  /internal/payments/mark-settled"], P)

    s.edge([ps.r(-14), (kf.x, ps.cy - 14)], "outbox → payments / orders.events", Y, at=(540, ps.cy - 14))
    s.edge([kf.r(-14), (os_.x, kf.cy - 14)], "consume (manual ack)", Y, at=(1070, kf.cy - 14))
    s.edge([os_.l(14), (kf.x + kf.w, os_.l(14)[1])], "outbox → settlements.events", Y, at=(1070, os_.cy + 14))

    s.listbox(1300, 810, "Every Feign call", [
        "Resilience4j @CircuitBreaker + @Retry",
        "instances configured in config-repo",
        "vault charge: thread-pool bulkhead",
        "lb://name locally · *_SERVICE_URI on k8s",
        "no auth on /internal — network isolation"], C["yellow"], mono=False)
    s.legend(60, 1005, [("solid", P, "synchronous Feign call"), ("solid", Y, "asynchronous event via the outbox")])
    return s


def deployment_topology():
    s = Scene(1640, 960, "Kubernetes deployment topology",
              "microservices/k8s applied with kubectl apply -k to a local kind cluster · namespace payflo")
    s.group(40, 120, 250, 780, "Your machine", GR, "terminal")
    s.group(330, 120, 1270, 780, "kind cluster · payflo", T, "kubernetes", sub="single node · kind-config.yaml")
    s.group(360, 180, 760, 520, "Applications", G, "box", sub="Deployments · Jib images payflo/<module>:latest")
    s.group(1160, 180, 410, 520, "Stateful", R, "db", sub="StatefulSets with PVCs")
    s.group(360, 760, 1210, 120, "Configuration", Y, "gear")

    br = s.node(60, 180, "curl / client", "browser", "localhost:8080", w=210, accent=B, icolor=B)
    jib = s.node(60, 420, "mvnw jib:dockerBuild", "apachemaven", "then kind load docker-image", w=210, accent=GR)
    kc = s.node(60, 640, "kubectl apply -k", "kubernetes", "microservices/k8s", w=210, accent=GR)

    gw = s.node(390, 230, "api-gateway-service", "spring", "NodePort 30080 → host :8080", layout="row", w=330, accent=O)
    cs = s.node(760, 230, "config-service", "spring", "profile native,k8s · bakes config-repo", layout="row", w=330,
                accent=T)
    ms = s.node(390, 370, "merchant-service", "springboot", "ClusterIP · JWT_SECRET, WEBHOOK key", layout="row", w=330,
                accent=G)
    vs = s.node(760, 370, "vault-service", "springboot", "ClusterIP · the only VAULT_MASTER_KEY", layout="row", w=330,
                accent=G)
    ps = s.node(390, 510, "payment-service", "springboot", "ClusterIP · DB password only", layout="row", w=330, accent=G)
    os_ = s.node(760, 510, "operations-service", "springboot", "ClusterIP · DB password only", layout="row", w=330,
                 accent=G)
    s.pill(740, 640, "requests 250m / 512Mi · limits 1 CPU / 1Gi · health probes on /actuator/health", G)

    pg = s.node(1190, 230, "postgres", "postgresql", "16 · 10Gi · 4 DBs, 1 user each", layout="row", w=350, accent=R)
    rd = s.node(1190, 350, "redis", "redis", "7 · 2Gi", layout="row", w=350, accent=R)
    kf = s.node(1190, 470, "kafka", "stream", "KRaft single node · 10Gi · :9092", layout="row", w=350, accent=R,
                icolor=Y)
    ku = s.node(1190, 590, "kafka-ui", "browser", "port-forward :8090", layout="row", w=350, accent=R, icolor=R)

    cm = s.node(390, 800, "ConfigMap app-config", "gear", "SPRING_PROFILES_ACTIVE=k8s · hosts · *_SERVICE_URI",
                layout="row", w=520, accent=Y, icolor=Y)
    sc = s.node(960, 800, "Secret app-secrets", "key", "secretGenerator ← secrets.env (gitignored)", layout="row",
                w=440, accent=Y, icolor=Y)

    s.edge([br.r(), (310, br.cy), (310, gw.cy), gw.l()], "localhost:8080", B, at=(310, 230))
    s.edge([jib.b(), kc.t()], "images loaded", GR, dashed=True)
    s.edge([kc.r(), (345, kc.cy), (345, 725), (740, 725)], None, GR, dashed=True)
    s.pill(540, 725, "namespace · manifests · Secret", GR)
    s.edge([gw.b(-100), (gw.cx - 100, 345), (365, 345), (365, ms.cy), ms.l()], None, G)
    s.pill(gw.cx - 30, 352, "http://<service> routes", G, mono=True)
    s.edge([ms.t(80), (ms.cx + 80, cs.cy + 20), cs.l(20)], "config at startup", T, dashed=True, at=(700, 300))
    s.edge([vs.r(-10), (1150, vs.cy - 10), (1150, pg.cy), pg.l()], None, R)
    s.edge([os_.r(), (1165, os_.cy), (1165, kf.cy), kf.l()], None, R)
    s.edge([os_.r(-12), (1140, os_.cy - 12), (1140, rd.cy), rd.l()], None, R)
    s.pill(1140, 690, "JDBC · Redis · Kafka", R)
    s.edge([cm.t(), (cm.cx, 700)], None, Y, dashed=True)
    s.edge([sc.t(), (sc.cx, 700)], None, Y, dashed=True)
    s.legend(60, 932, [("solid", B, "only public entry point"), ("solid", G, "in-cluster HTTP"), ("solid", R, "data"),
                        ("dashed", T, "config server"), ("dashed", Y, "env from ConfigMap / Secret"),
                        ("dashed", GR, "build and deploy")])
    return s
