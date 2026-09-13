"""
Entity-relationship diagrams for the four service databases, taken from each service's JPA entities.

Handles: every table and column, primary/foreign/unique keys, real foreign-key relationships (crow's feet), and plain-id
references into another service's database (marked ID). Every table also carries BaseEntity's four audit columns
(created_at, updated_at, created_by, updated_by), which are left off the cards to keep them readable.
"""
from kit import ER, C, MUTED

G, B, P, O, Y, R, T, GR = C["green"], C["blue"], C["purple"], C["orange"], C["yellow"], C["red"], C["teal"], C["gray"]
AUDIT = "+ BaseEntity audit columns"


def merchant():
    e = ER(1480, 820, "merchant-service database — payflo_merchant",
           "Merchants, dashboard users, API keys, customers and webhook configs · schema by Hibernate ddl-auto: update")
    m = e.entity("m", 560, 120, "merchant", [("id", "uuid", "PK"), ("name", "varchar(200)"), ("email", "varchar", "UK"),
                 ("contact_number", "varchar"), ("business_type", "varchar"), ("business_name", "varchar"),
                 ("website_url", "varchar"), ("status", "varchar"), ("gst_id", "varchar"), ("pan_id", "varchar"),
                 ("settlement_bank_account", "varchar"), ("settlement_bank_ifsc", "varchar"),
                 ("settlement_bank_account_holder_name", "varchar")], B, w=380, note=AUDIT)
    u = e.entity("u", 60, 120, "app_user", [("id", "uuid", "PK"), ("merchant_id", "uuid", "FK"), ("email", "varchar", "UK"),
                 ("password_hash", "varchar"), ("role", "varchar")], G, w=300, note=AUDIT)
    k = e.entity("k", 60, 420, "api_key", [("id", "uuid", "PK"), ("merchant_id", "uuid", "FK"), ("key_id", "varchar(50)", "UK"),
                 ("key_secret_hash", "varchar(200)"), ("previous_key_secret_hash", "varchar"), ("environment", "varchar(10)"),
                 ("enabled", "boolean"), ("last_used_at", "timestamp"), ("rotated_at", "timestamp"),
                 ("grace_period_expires_at", "timestamp")], O, w=300, note=AUDIT)
    c = e.entity("c", 1100, 120, "customer", [("id", "uuid", "PK"), ("merchant_id", "uuid", "FK"), ("name", "varchar"),
                 ("email", "varchar"), ("phone", "varchar"), ("deleted_at", "timestamp")], P, w=320,
                 note="found or created by merchant + email")
    w = e.entity("w", 1100, 460, "merchant_webhook_config", [("id", "uuid", "PK"), ("merchant_id", "uuid", "FK"),
                 ("target_url", "varchar(500)"), ("webhook_secret", "varchar"), ("enabled", "boolean"),
                 ("event_types", "varchar")], T, w=320, note="secret AES-encrypted at rest")
    e.rel([(m.x, u.cy), u.r()], "one", "zmany", "has users")
    e.rel([(m.x + 60, m.y + m.h), (m.x + 60, k.cy), k.r()], "one", "zmany", "has keys", at=(m.x - 60, k.cy))
    e.rel([(m.x + m.w, c.cy), c.l()], "one", "zmany", "has")
    e.rel([(m.x + m.w - 60, m.y + m.h), (m.x + m.w - 60, w.cy), w.l()], "one", "zmany", "configures", at=(m.x + m.w + 40, w.cy))
    e.s.pill(740, 780, "customers, orders and cards elsewhere reference merchant.id as a plain uuid — no cross-database FK", MUTED)
    return e.s


def payment():
    e = ER(1560, 900, "payment-service database — payflo_payment",
           "Orders, payments, refunds, the transition log and the transactional outbox · merchant and customer ids are plain uuids")
    o = e.entity("o", 60, 120, "order_record", [("id", "uuid", "PK"), ("merchant_id", "uuid", "ID"), ("customer_id", "uuid", "ID"),
                 ("amount_units", "integer"), ("currency", "varchar"), ("receipt", "varchar"), ("order_status", "varchar(20)"),
                 ("attempts", "int"), ("notes", "jsonb"), ("expires_at", "timestamp")], B, w=320,
                 note="unique (merchant_id, receipt)")
    p = e.entity("p", 560, 120, "payment", [("id", "uuid", "PK"), ("order_id", "uuid", "FK"), ("merchant_id", "uuid", "ID"),
                 ("amount_units", "integer"), ("currency", "varchar"), ("idempotency_key", "varchar(100)"),
                 ("status", "varchar(30)"), ("method", "varchar"), ("method_details", "jsonb"), ("bank_reference", "varchar"),
                 ("processor_reference", "varchar"), ("error_code", "varchar"), ("error_description", "varchar"),
                 ("authorized_at", "timestamp"), ("captured_at", "timestamp"), ("failed_at", "timestamp"),
                 ("refunded_at", "timestamp"), ("settled_at", "timestamp")], G, w=340,
                 note="unique (merchant_id, idempotency_key)")
    t = e.entity("t", 1080, 120, "payment_transition_log", [("id", "uuid", "PK"), ("payment_id", "uuid", "FK"),
                 ("from_status", "varchar(30)"), ("event", "varchar(30)"), ("to_status", "varchar(30)"), ("actor", "varchar(100)"),
                 ("occurred_at", "timestamp")], Y, w=380, note="one row per state-machine transition")
    r = e.entity("r", 1080, 460, "refund", [("id", "uuid", "PK"), ("payment_id", "uuid", "FK"), ("merchant_id", "uuid", "ID"),
                 ("amount_units", "integer"), ("currency", "varchar"), ("status", "varchar"), ("bank_reference", "varchar"),
                 ("error_code", "varchar"), ("error_description", "varchar"), ("notes", "jsonb"), ("processed_at", "timestamp")],
                 R, w=380, note="table only — refunds aren't built yet")
    x = e.entity("x", 60, 560, "outbox_event", [("id", "uuid", "PK"), ("aggregate_type", "varchar"), ("aggregate_id", "uuid"),
                 ("event_type", "varchar(50)"), ("payload", "jsonb"), ("status", "varchar"), ("attempts", "int"),
                 ("last_error", "varchar"), ("published_at", "timestamp")], O, w=320,
                 note="written in the same transaction as the change")
    e.rel([o.r(-60), (p.x, o.cy - 60)], "one", "zmany", "attempted by")
    e.rel([(p.x + p.w, t.cy), t.l()], "one", "zmany", "logged by")
    e.rel([(p.cx + 100, p.y + p.h), (p.cx + 100, r.cy), r.l()], "one", "zmany", "refunded by", at=(p.cx + 230, r.cy))
    e.s.pill(780, 865, "outbox_event has no FK: aggregate_id is an order or payment id, published to Kafka by OutboxPoller", MUTED)
    return e.s


def vault():
    e = ER(1200, 640, "vault-service database — payflo_vault",
           "The only database that holds card data · envelope-encrypted PAN, tokens handed back to merchants")
    v = e.entity("v", 60, 120, "vault_card", [("id", "uuid", "PK"), ("last_four", "varchar(4)"), ("bin", "varchar(6)"),
                 ("encrypted_pan", "bytea"), ("encrypted_dek", "bytea"), ("brand", "varchar"), ("expiry_month", "varchar"),
                 ("expiry_year", "varchar"), ("card_holder_name", "varchar"), ("deleted_at", "timestamp")], P, w=360,
                 note="PAN under a per-card DEK; DEK wrapped by the master key")
    t = e.entity("t", 720, 120, "card_token", [("id", "uuid", "PK"), ("token", "varchar(50)", "UK"),
                 ("vault_card_id", "uuid", "FK"), ("customer", "uuid", "ID"), ("merchant", "uuid", "ID"),
                 ("revoked_at", "timestamp")], O, w=380, note="the only thing a merchant ever sees")
    e.rel([(v.x + v.w, t.cy), t.l()], "one", "zmany", "tokenized as")
    e.s.pill(600, 590, "CVV is validated on tokenize and never stored", MUTED)
    return e.s


def operations():
    e = ER(1520, 940, "operations-service database — payflo_operations",
           "Webhook deliveries, the dead-letter queue, settlements and this service's own outbox")
    w = e.entity("w", 60, 120, "webhook_event", [("id", "uuid", "PK"), ("merchant_id", "uuid", "ID"), ("event_type", "varchar(100)"),
                 ("payload", "jsonb"), ("target_url", "varchar"), ("signature", "varchar"), ("status", "varchar"),
                 ("attempts", "int"), ("next_retry_at", "timestamp"), ("last_attempt_at", "timestamp"),
                 ("last_response_code", "int"), ("last_response_body", "varchar"), ("delivered_at", "timestamp")], O, w=340)
    d = e.entity("d", 60, 580, "dlq_event", [("id", "uuid", "PK"), ("merchant_id", "uuid", "ID"),
                 ("webhook_event_id", "uuid", "FK"), ("final_error", "varchar"), ("payload", "jsonb"),
                 ("moved_at", "timestamp"), ("replayed_at", "timestamp")], R, w=340, note="webhook_event_id null if it failed before one existed")
    s = e.entity("s", 560, 120, "settlement", [("id", "uuid", "PK"), ("merchant_id", "uuid", "ID"),
                 ("gross_amount_units", "integer"), ("gross_amount_currency", "varchar"), ("refund_amount_units", "integer"),
                 ("refund_amount_currency", "varchar"), ("fee_amount_units", "integer"), ("fee_amount_currency", "varchar"),
                 ("gst_amount_units", "integer"), ("gst_amount_currency", "varchar"), ("net_amount_units", "integer"),
                 ("net_amount_currency", "varchar"), ("status", "varchar(20)"), ("bank_reference", "varchar(50)"),
                 ("processed_at", "timestamp"), ("failure_reason", "varchar")], G, w=360)
    sp = e.entity("sp", 1060, 120, "settlement_payment", [("settlement_id", "uuid", "PK FK"), ("payment_id", "uuid", "PK")],
                  B, w=380, note="payment_id → payment-service payment.id")
    x = e.entity("x", 1060, 460, "outbox_event", [("id", "uuid", "PK"), ("aggregate_type", "varchar"), ("aggregate_id", "uuid"),
                 ("event_type", "varchar(50)"), ("payload", "jsonb"), ("status", "varchar"), ("attempts", "int"),
                 ("last_error", "varchar"), ("published_at", "timestamp")], Y, w=380, note="settlement events only")
    e.rel([(w.cx, w.y + w.h), (w.cx, d.y)], "one", "zone", "dead-lettered")
    e.rel([(s.x + s.w, sp.cy), sp.l()], "one", "zmany", "includes")
    return e.s
