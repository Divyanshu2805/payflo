"""
State-machine diagrams: the payment state machine, a settlement's status, and webhook-event / outbox delivery status.

Handles: PaymentStateMachine's transition table (solid where payment-service fires the event, dashed where the
transition is defined but nothing fires it yet), SettlementTransactionExecutor's status changes, and the delivery
status moves made by WebhookDeliverExecutor, WebhookDlqRecorder and OutboxResultHandler.
"""
from kit import Scene, C, LINE, MUTED

G, B, P, O, Y, R, T, GR = C["green"], C["blue"], C["purple"], C["orange"], C["yellow"], C["red"], C["teal"], C["gray"]


def payment():
    s = Scene(1800, 820, "Payment state machine — PaymentStatus × PaymentEvent",
              "PaymentStateMachine's transition table · every applied transition writes a payment_transition_log row")
    s.group(40, 130, 1720, 560, "payment-service", G, "layers", sub="PaymentTransitionService · undefined pair → 409")
    xs = [70 + i * 290 for i in range(6)]
    w, h, y1, y2 = 190, 88, 250, 500

    def st(i, y, name, sub, col, badge=None):
        return s.node(xs[i], y, name, None, sub, w=w, h=h, accent=col, badge=badge)

    cr = st(0, y1, "CREATED", "saved by recordPayment", B)
    au = st(1, y1, "AUTHORIZING", "at the acquirer", Y)
    ad = st(2, y1, "AUTHORIZED", "funds held", G)
    cp = st(3, y1, "CAPTURING", "capture in flight", Y)
    ca = st(4, y1, "CAPTURED", "money taken", G)
    se = st(5, y1, "SETTLED", "paid out to merchant", T, "END")
    cn = st(0, y2, "CANCELLED", "", GR, "END")
    fl = st(1, y2, "FAILED", "errorCode set", R, "END")
    ex = st(2, y2, "AUTH_EXPIRED", "hold lapsed", GR, "END")
    pr = st(4, y2, "PARTIALLY_REFUNDED", "refund in progress", O)
    rf = st(5, y2, "REFUNDED", "", O, "END")

    def right(a, b, label, col, dashed=False, dy=0):
        s.edge([a.r(dy), (b.x, a.cy + dy)], None, col, dashed=dashed)
        s.pill((a.x + a.w + b.x) / 2, a.cy + dy - 22, label, col if not dashed else MUTED, size=9.5)

    right(cr, au, "AUTHORIZE_ATTEMPT", B)
    right(au, ad, "AUTHORIZE_SUCCESS", G)
    right(ad, cp, "CAPTURE_REQUEST", G)
    right(cp, ca, "CAPTURE_SUCCESS", G)
    right(ca, se, "SETTLE", GR, dashed=True)
    s.edge([cp.t(), (cp.cx, 190), (ad.cx, 190), ad.t()], "CAPTURE_FAIL · retryable", R, at=((cp.cx + ad.cx) / 2, 190))
    s.edge([au.b(), fl.t()], "AUTHORIZE_FAIL", R, at=(au.cx, 440))
    s.edge([cr.b(), cn.t()], "CANCEL", GR, dashed=True, at=(cr.cx, 440))
    s.edge([au.b(-60), (au.cx - 60, 420), (cn.cx + 60, 420), cn.t(60)], None, GR, dashed=True)
    s.edge([ad.b(), ex.t()], "CAPTURE_TIMEOUT", GR, dashed=True, at=(ad.cx, 440))
    s.edge([ca.b(), pr.t()], "REFUND_INIT", GR, dashed=True, at=(ca.cx, 440))
    s.edge([se.b(-40), (se.cx - 40, 420), (pr.cx + 40, 420), pr.t(40)], None, GR, dashed=True)
    s.edge([pr.r(), rf.l()], None, GR, dashed=True)
    s.pill((pr.x + pr.w + rf.x) / 2, pr.cy - 22, "REFUND_COMPLETE", MUTED, size=9.5)
    s.edge([ca.b(60), (ca.cx + 60, 470), (rf.cx, 470), rf.t()], "REFUND_COMPLETE · full refund", GR, dashed=True,
           at=(rf.cx - 40, 470))

    s.listbox(70, 715, "WHO FIRES WHAT", ["AUTHORIZE_ATTEMPT / _FAIL — the payment saga (POST /v1/payments)",
                                         "AUTHORIZE_SUCCESS, CAPTURE_* — BankCallbackSimulator, and POST …/capture"], G,
              mono=False)
    s.listbox(900, 715, "DEFINED BUT NEVER FIRED", ["SETTLED is set directly by /internal/payments/mark-settled",
                                                   "CANCEL, CAPTURE_TIMEOUT, REFUND_* — no caller yet"], GR, mono=False)
    return s


def settlement():
    s = Scene(1400, 560, "Settlement status — SettlementStatus",
              "One settlement per merchant per nightly run · moved by SettlementTransactionExecutor")
    s.group(40, 130, 1320, 330, "operations-service", G, "bank")
    ini = s.node(80, 230, "INITIATED", None, "amounts computed · links saved", w=240, h=88, accent=B)
    tp = s.node(440, 230, "TRANSFER_PENDING", None, "bank transfer requested", w=260, h=88, accent=Y)
    pr = s.node(820, 180, "PROCESSED", None, "payments marked SETTLED", w=250, h=88, accent=G, badge="END")
    fl = s.node(820, 330, "FAILED", None, "failure_reason set", w=250, h=88, accent=R, badge="END")
    s.edge([ini.r(), tp.l()], "bank accepts", Y)
    s.edge([tp.r(-20), (760, tp.cy - 20), (760, pr.cy), pr.l()], "callback OK", G, at=(760, 215))
    s.edge([tp.r(20), (740, tp.cy + 20), (740, fl.cy), fl.l()], "callback error", R, at=(740, 380))
    s.edge([ini.b(), (ini.cx, fl.cy + 20), fl.l(20)], "any exception during the run", R, at=(300, fl.cy + 20))
    s.pill(1180, pr.cy, "→ SETTLEMENT_PROCESSED", G)
    s.pill(1180, fl.cy, "→ SETTLEMENT_FAILED", R)
    s.text(60, 510, "The callback simulator always reports success today, so the callback-error branch is never taken.",
           12, MUTED)
    return s


def delivery():
    s = Scene(1500, 760, "Delivery status — WebhookEventStatus and OutboxStatus",
              "How a webhook delivery and an outbox row move from written to done")
    s.group(40, 130, 1420, 330, "webhook_event · operations-service", O, "webhook")
    s.group(40, 510, 1420, 180, "outbox_event · payment-service and operations-service", Y, "stream")

    pe = s.node(80, 240, "PENDING", None, "signed, queued in Redis", w=230, h=88, accent=B)
    de = s.node(560, 180, "DELIVERED", None, "2xx from the merchant", w=240, h=88, accent=G, badge="END")
    fa = s.node(560, 330, "FAILED", None, "next_retry_at scheduled", w=240, h=88, accent=Y)
    dd = s.node(1060, 330, "DEAD", None, "DlqEvent written", w=240, h=88, accent=R, badge="END")
    s.edge([pe.r(-20), (440, pe.cy - 20), (440, de.cy), de.l()], "2xx", G, at=(440, 215))
    s.edge([pe.r(20), (460, pe.cy + 20), (460, fa.cy), fa.l()], "error / timeout", Y, at=(460, 380))
    s.edge([fa.t(40), (fa.cx + 40, 300), (de.cx + 40, 300), de.b(40)], None, G)
    s.pill(fa.cx + 120, 300, "retry succeeds", G)
    s.edge([fa.r(-14), (dd.x, fa.cy - 14)], "7th failed attempt", R, at=(930, fa.cy - 36))
    s.edge([fa.b(), (fa.cx, 440), (fa.cx - 90, 440), (fa.cx - 90, fa.y + fa.h)], None, Y)
    s.pill(fa.cx + 110, 440, "retry fails · 1m → 24h", Y)

    op = s.node(80, 570, "PENDING", None, "same transaction as the change", w=260, h=88, accent=B)
    ou = s.node(560, 570, "PUBLISHED", None, "sent to *.events", w=240, h=88, accent=G, badge="END")
    of = s.node(1060, 570, "FAILED", None, "3 attempts · no further retry", w=260, h=88, accent=R, badge="END")
    s.edge([op.r(), ou.l()], "OutboxPoller · 5 s", G)
    s.edge([op.b(), (op.cx, 680), (of.cx, 680), of.b()], "third publish error", R, at=(700, 680))
    return s
