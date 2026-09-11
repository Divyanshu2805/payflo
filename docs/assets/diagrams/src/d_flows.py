"""
Request-flow diagrams: gateway authentication, an order and payment end to end, webhook delivery, and nightly settlement.

Handles: sequence diagrams traced from GatewayAuthFilter and its handlers, OrderServiceImpl and the payment saga in
PaymentAuthorizationRecorder, BankCallbackSimulator, the outbox poller, the webhook pipeline in operations-service, and
SettlementEngine / SettlementTransactionExecutor / BankSettlementCallbackSimulator.
"""
from kit import Sequence, C

G, B, P, O, Y, R, T, GR = C["green"], C["blue"], C["purple"], C["orange"], C["yellow"], C["red"], C["teal"], C["gray"]


def auth():
    q = Sequence("Flow — authentication at the gateway",
                 "merchant-service issues credentials; the gateway verifies every call and forwards the caller's identity",
                 [dict(id="cl", label="Merchant", sub="dashboard or backend", icon="user", color=B, icolor=B),
                  dict(id="gw", label="api-gateway", sub="GatewayAuthFilter", icon="spring", color=O),
                  dict(id="rd", label="Redis", sub="ApiKeyCache · limiter", icon="redis", color=R),
                  dict(id="ms", label="merchant-service", sub="AuthController", icon="springboot", color=G),
                  dict(id="ds", label="business service", sub="MerchantContextFilter", icon="springboot", color=G)],
                 col=250)
    q.msg("cl", "gw", "POST /v1/auth/signup · public route")
    q.msg("gw", "ms", "route /v1/auth/**")
    q.msg("ms", "ms", "Merchant PENDING_KYC + AppUser OWNER · bcrypt")
    q.msg("cl", "gw", "POST /v1/auth/login { email, password }")
    q.msg("gw", "ms", "route /v1/auth/**")
    q.msg("ms", "cl", "JWT · merchant_id + role · 100 min", ret=True)
    q.frame_start("alt", "Authorization: Bearer <jwt>")
    q.msg("cl", "gw", "POST /v1/merchants/api-keys")
    q.msg("gw", "gw", "JwtAuthHandler · verify with jwt.secret-key")
    q.msg("gw", "ms", "+ X-Merchant-Id · X-User-Role")
    q.msg("ms", "cl", "keyId fp_<env>_… + secret, shown once", ret=True)
    q.frame_end()
    q.frame_start("alt", "Authorization: Basic keyId:secret", B)
    q.msg("cl", "gw", "POST /v1/orders")
    q.msg("gw", "rd", "GET apikey:<keyId>", color=R)
    q.frame_start("opt", "cache miss", P)
    q.msg("gw", "ms", "GET /internal/api-keys/{keyId}", color=P)
    q.msg("gw", "rd", "SET entry · 5 min TTL", color=R)
    q.frame_end()
    q.msg("gw", "gw", "bcrypt: current secret, or previous in 24 h grace")
    q.msg("gw", "rd", "rate limit · 200 / min per key", color=R)
    q.msg("gw", "ds", "+ X-Merchant-Id · X-Key-Id · X-Environment")
    q.msg("ds", "ds", "MerchantContext.getMerchantId()")
    q.frame_end()
    q.note(["cl", "ds"], "no credential or bad one → 401 · over the limit → 429 + Retry-After · client-sent X-Merchant-Id is overwritten")
    return q.render()


def payment():
    q = Sequence("Flow — an order and a card payment",
                 "No remote call runs inside a database transaction; every state change goes through the state machine",
                 [dict(id="mb", label="Merchant backend", sub="API key", icon="code", color=B, icolor=B),
                  dict(id="ps", label="payment-service", sub="Order / PaymentController", icon="springboot", color=G),
                  dict(id="ms", label="merchant-service", sub="internal API", icon="springboot", color=G),
                  dict(id="vs", label="vault-service", sub="internal API · PCI", icon="lock", color=P, icolor=P),
                  dict(id="db", label="payflo_payment", sub="PostgreSQL", icon="postgresql", color=R),
                  dict(id="kf", label="Kafka", sub="*.events", icon="stream", color=Y, icolor=Y)],
                 col=225)
    q.msg("mb", "ps", "POST /v1/orders { amount, customer? }  (via gateway)")
    q.msg("ps", "ms", "POST /internal/customers/find-or-create", color=P)
    q.msg("ps", "db", "tx: order CREATED + ORDER_CREATED outbox row", color=R)
    q.msg("mb", "ps", "POST /v1/payments { orderId, CARD, token }")
    q.msg("ps", "db", "tx 1 · lock order · Payment · CREATED → AUTHORIZING", color=R)
    q.msg("ps", "vs", "no tx · POST /internal/vault/charge { token, amount }", color=P)
    q.msg("vs", "vs", "decrypt PAN · mock acquirer · bulkhead")
    q.msg("vs", "ps", "PENDING (processorReference) or FAILURE", ret=True)
    q.msg("ps", "db", "tx 2 · apply result + PAYMENT_CREATED outbox", color=R)
    q.msg("ps", "mb", "201 · status AUTHORIZING (or FAILED + errorCode)", ret=True)
    q.note(["ps", "vs"], "vault down or circuit open → compensate: FAILED + PAYMENT_AUTHORIZATION_COMPENSATED", color=R)
    q.frame_start("loop", "BankCallbackSimulator · every 5 s · ShedLock", O)
    q.msg("ps", "db", "AUTHORIZING past the simulated bank delay", color=R)
    q.msg("ps", "ps", "AUTHORIZE_SUCCESS → CAPTURE_REQUEST → CAPTURE_SUCCESS")
    q.msg("ps", "db", "payment CAPTURED · order PAID · PAYMENT_STATUS_CHANGED", color=R)
    q.frame_end()
    q.frame_start("loop", "OutboxPoller · every 5 s · ShedLock", Y)
    q.msg("ps", "kf", "publish PENDING rows → mark PUBLISHED", color=Y)
    q.frame_end()
    return q.render()


def webhooks():
    q = Sequence("Flow — webhook delivery",
                 "A domain event becomes one HMAC-signed delivery per subscribed target, retried for up to 24 hours",
                 [dict(id="kf", label="Kafka", sub="payments/orders/settlements", icon="stream", color=Y, icolor=Y),
                  dict(id="op", label="operations-service", sub="WebhookKafkaConsumer", icon="springboot", color=G),
                  dict(id="ms", label="merchant-service", sub="internal API", icon="springboot", color=G),
                  dict(id="rd", label="Redis", sub="WebhookRetryQueue", icon="redis", color=R),
                  dict(id="db", label="payflo_operations", sub="PostgreSQL", icon="postgresql", color=R),
                  dict(id="mh", label="Merchant endpoint", sub="target_url", icon="webhook", color=O, icolor=O)],
                 col=225)
    q.msg("kf", "op", "event envelope · group operations-service", color=Y)
    q.msg("op", "ms", "GET /internal/merchants/{id}/webhook-targets?eventType=", color=P)
    q.msg("ms", "op", "targets + decrypted signing secrets", ret=True)
    q.msg("op", "db", "one WebhookEvent PENDING per target · HMAC-SHA256", color=R)
    q.msg("op", "rd", "ZADD due-at", color=R)
    q.msg("op", "kf", "manual ack", ret=True)
    q.frame_start("loop", "WebhookDeliveryScheduler · every 1 s · ShedLock · virtual threads", O)
    q.msg("op", "rd", "due entries", color=R)
    q.msg("op", "mh", "POST payload + X-PayFlo-Signature · 3 s / 5 s timeouts", color=O)
    q.frame_start("alt", "2xx", G)
    q.msg("op", "db", "DELIVERED · delivered_at", color=R)
    q.frame_end()
    q.frame_start("alt", "error or timeout", R)
    q.msg("op", "db", "FAILED · next_retry_at = 1m, 5m, 30m, 2h, 8h, 24h", color=R)
    q.msg("op", "rd", "re-queue", color=R)
    q.frame_end()
    q.frame_end()
    q.note(["op", "db"], "7th failure → WebhookDlqRecorder: DEAD + DlqEvent (own transaction)", color=R)
    q.note(["op", "rd"], "every 10 s: rows whose next_retry_at passed with no queue entry are re-queued")
    return q.render()


def settlement():
    q = Sequence("Flow — nightly settlement",
                 "At 23:00 each active merchant's captured, unsettled payments are paid out in one transfer",
                 [dict(id="op", label="operations-service", sub="SettlementEngine", icon="springboot", color=G),
                  dict(id="ms", label="merchant-service", sub="internal API", icon="springboot", color=G),
                  dict(id="ps", label="payment-service", sub="internal API", icon="springboot", color=G),
                  dict(id="db", label="payflo_operations", sub="PostgreSQL", icon="postgresql", color=R),
                  dict(id="bk", label="Mock bank", sub="BankTransferProcessor", icon="bank", color=O, icolor=O)],
                 col=250)
    q.msg("op", "ms", "GET /internal/merchants/active-ids", color=P)
    q.frame_start("loop", "each merchant · a virtual thread · @Transactional", G)
    q.msg("op", "ps", "GET /internal/payments/unsettled-captured?merchantId=", color=P)
    q.msg("op", "op", "gross · fee 2% · GST 18% of fee · net")
    q.msg("op", "db", "Settlement INITIATED + SettlementPayment links", color=R)
    q.msg("op", "ms", "GET /internal/merchants/{id}/settlement-bank-details", color=P)
    q.msg("op", "bk", "transfer net amount", color=O)
    q.msg("bk", "op", "TXN_… reference", ret=True)
    q.msg("op", "db", "TRANSFER_PENDING · bank_reference", color=R)
    q.frame_end()
    q.frame_start("loop", "BankSettlementCallbackSimulator · every 5 s · ShedLock", O)
    q.msg("op", "db", "TRANSFER_PENDING settlements", color=R)
    q.msg("op", "db", "PROCESSED · processed_at", color=R)
    q.msg("op", "ps", "POST /internal/payments/mark-settled", color=P)
    q.msg("op", "db", "SETTLEMENT_PROCESSED outbox row → Kafka → webhooks", color=R)
    q.frame_end()
    q.note(["op", "ps"], "every Feign call goes through SettlementIntegrationGateway · circuit breaker + retry")
    return q.render()
