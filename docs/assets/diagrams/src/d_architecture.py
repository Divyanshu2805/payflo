"""
The system-architecture diagram: every runtime component of the microservices build and every real connection.

Handles: the two kinds of caller, the gateway's centralized auth and routes, Eureka and the native config server, the
four business services and their Feign calls, the per-service databases, Redis, Kafka, and the simulated bank side.
"""
from kit import Scene, C, LINE

G, B, P, O, Y, R, T, GR = C["green"], C["blue"], C["purple"], C["orange"], C["yellow"], C["red"], C["teal"], C["gray"]


def build():
    s = Scene(1760, 1120, "PayFlo — system architecture",
              "The microservices build under microservices/ — every runtime component and connection, as wired in the code")

    s.group(40, 270, 210, 760, "Merchants", GR, "user")
    s.group(290, 420, 230, 250, "Edge", O, "shield")
    s.group(580, 110, 700, 120, "Platform", T, "gear", sub="every service registers and pulls config at startup")
    s.group(580, 270, 700, 760, "Business services", G, "box")
    s.group(1330, 270, 390, 760, "Data and messaging", R, "db")

    dash = s.node(60, 330, "Dashboard", "browser", "a merchant's staff", w=170, accent=B, icolor=B)
    back = s.node(60, 520, "Merchant backend", "code", "server-to-server", w=170, accent=B, icolor=B)
    hook = s.node(60, 850, "Webhook endpoint", "webhook", "the merchant's URL", w=170, accent=O, icolor=O)

    gw = s.node(305, 470, "api-gateway", "spring", ":8080", w=200, h=150, accent=O)
    s.pill(405, 588, "JWT · API key · rate limit", O)

    eur = s.node(610, 145, "discovery-service", "spring", "Eureka · :8761", layout="row", w=300, accent=T)
    cfg = s.node(950, 145, "config-service", "spring", ":8888 · config-repo/*.yaml", layout="row", w=300, accent=T)

    ms = s.node(620, 310, "merchant-service", "springboot", ":8081 · merchants, users, API keys, webhooks", layout="row",
                w=400, accent=G)
    ps = s.node(620, 490, "payment-service", "springboot", ":8082 · orders, payments, state machine, outbox", layout="row",
                w=400, accent=G)
    vs = s.node(620, 670, "vault-service", "springboot", ":8083 · card vault — the only PCI-scoped service", layout="row",
                w=400, accent=G)
    os_ = s.node(620, 850, "operations-service", "springboot", ":8084 · webhook delivery, nightly settlement",
                 layout="row", w=400, accent=G)
    s.pill(1205, 540, "bank callback simulator", GR)
    s.pill(1205, 720, "mock card acquirer", GR)
    s.pill(1205, 900, "mock bank transfer", GR)

    pg = s.node(1360, 320, "PostgreSQL", "postgresql", "payflo_merchant · _payment", layout="row", w=330, accent=R)
    s.pill(1525, 400, "payflo_vault · payflo_operations", R, mono=True)
    s.pill(1525, 428, "JDBC · one database per service", R)
    rd = s.node(1360, 540, "Redis", "redis", "API-key cache · rate limits", layout="row", w=330, accent=R)
    s.pill(1525, 620, "idempotency · retry queue · ShedLock", R)
    kf = s.node(1360, 780, "Kafka", "stream", "payments · orders · settlements", layout="row", w=330, accent=Y, icolor=Y)
    s.pill(1525, 860, "*.events topics · via outbox", Y)

    s.edge([dash.r(), (268, dash.cy), (268, gw.cy - 30), gw.l(-30)], "Bearer JWT", B, at=(268, 420))
    s.edge([back.r(), (268, back.cy), (268, gw.cy + 20), gw.l(20)], None, B)
    s.pill(160, 640, "Basic keyId:secret", B)

    bus = 560
    s.edge([gw.r(-40), (bus, gw.cy - 40), (bus, ms.cy), ms.l()], "/v1/auth · /v1/merchants", G, at=(bus, 400), mono=True)
    s.edge([gw.r(-10), (bus + 6, gw.cy - 10), (bus + 6, ps.cy), ps.l()], None, G)
    s.edge([gw.r(20), (bus + 12, gw.cy + 20), (bus + 12, vs.cy), vs.l()], None, G)
    s.edge([gw.r(40), (bus + 18, gw.cy + 40), (bus + 18, os_.cy - 10), os_.l(-10)], None, G)
    s.pill(bus - 30, 700, "/v1/orders · /v1/payments", G, mono=True)
    s.pill(bus - 30, 730, "/v1/vault · /webhook", G, mono=True)

    s.edge([gw.t(), (gw.cx, eur.cy), eur.l()], "lb:// lookup", LINE, dashed=True, at=(gw.cx, 300))
    s.edge([eur.r(), cfg.l()], None, LINE, dashed=True)

    s.edge([(ms.x + 330, ps.y), (ms.x + 330, ms.y + ms.h)], None, P, dashed=True)
    s.pill(ms.x + 330, 450, "find-or-create customer", P)
    s.edge([(ps.x + 330, ps.y + ps.h), (ps.x + 330, vs.y)], None, P, dashed=True)
    s.pill(ps.x + 330, 630, "charge card token", P)
    s.edge([os_.r(-12), (1060, os_.cy - 12), (1060, ps.cy + 14), ps.r(14)], None, P, dashed=True)
    s.edge([os_.r(12), (1080, os_.cy + 12), (1080, ms.cy), ms.r()], None, P, dashed=True)
    s.pill(1080, 360, "targets · bank details", P)
    s.pill(1060, 800, "unsettled · mark settled", P)
    s.edge([gw.t(40), (gw.cx + 40, 250), (ms.cx + 150, 250), ms.t(150)], "GET /internal/api-keys on cache miss", P,
           dashed=True, at=(760, 250))

    s.edge([ms.r(-18), (1310, ms.cy - 18), (1310, pg.cy - 16), pg.l(-16)], None, R)
    s.edge([ps.r(-18), (1300, ps.cy - 18), (1300, pg.cy - 4), pg.l(-4)], None, R)
    s.edge([vs.r(-18), (1290, vs.cy - 18), (1290, pg.cy + 8), pg.l(8)], None, R)
    s.edge([os_.r(-26), (1280, os_.cy - 26), (1280, pg.cy + 20), pg.l(20)], None, R)
    s.edge([gw.b(), (gw.cx, 1060), (1700, 1060), (1700, rd.cy), rd.r()], "cache · rate-limit counters", R, at=(1000, 1060))
    s.edge([ps.r(26), (1316, ps.cy + 26), (1316, kf.cy - 12), kf.l(-12)], "outbox", Y, at=(1316, 700))
    s.edge([kf.l(12), (1300, kf.cy + 12), (1300, os_.cy + 26), os_.r(26)], "consume", Y, at=(1300, 862))
    s.edge([os_.l(20), (hook.r(0)[0] + 30, os_.cy + 20), (hook.r(0)[0] + 30, hook.cy), hook.r()],
           "signed POST · X-PayFlo-Signature", O, at=(420, os_.cy + 20))

    s.legend(60, 1094, [("solid", B, "merchant calls"), ("solid", G, "gateway route"),
                        ("dashed", P, "internal Feign call"), ("solid", R, "data"), ("solid", Y, "events"),
                        ("solid", O, "outbound webhook"), ("dashed", LINE, "discovery")])
    return s
