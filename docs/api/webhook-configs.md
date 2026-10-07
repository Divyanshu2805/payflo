# Webhook Configs

The endpoints a merchant wants events delivered to. **Service:** merchant-service · **Controller:** `WebhookConfigController` (`/v1/merchants/webhooks`)

| Method | Path | Request | Response | Notes |
|---|---|---|---|---|
| `POST` | `/v1/merchants/webhooks` | `UpdateWebhookConfigRequest { targetUrl, eventTypes?, enabled? }` | `200` `WebhookConfigResponse { id, targetUrl, webhookSecret, enabled, eventTypes }` | The server generates the signing secret. **`webhookSecret` appears only in this response**; it is stored AES-encrypted. |
| `GET` | `/v1/merchants/webhooks` | — | `200` `List<WebhookConfigResponse>` | Without `webhookSecret`. |
| `GET` | `/v1/merchants/webhooks/{id}` | — | `200` `WebhookConfigResponse` | `404` for an unknown id or another merchant's config. |
| `PUT` | `/v1/merchants/webhooks/{id}` | `UpdateWebhookConfigRequest` | `200` `WebhookConfigResponse` | Changes `targetUrl` and `eventTypes`, and `enabled`: `false` pauses deliveries to this endpoint without deleting it, `true` resumes them (omit to leave it). A change can take up to 30 seconds to apply. To change the secret use `rotate-secret`. |
| `POST` | `/v1/merchants/webhooks/{id}/rotate-secret` | — | `200` `WebhookConfigResponse` with a new `webhookSecret` | The new secret is effective at once and shown only here. Each attempt is signed with the secret as it is when the attempt is sent, so a retry after the rotation is signed with the new secret (within about 30 seconds: operations-service remembers a secret that long). |
| `DELETE` | `/v1/merchants/webhooks/{id}` | — | `204` | |

| Field | Constraint |
|---|---|
| `targetUrl` | required, at most 255 characters, must start with `http://` or `https://`. Beyond that it must be `https`, carry no credentials, and resolve only to public addresses — otherwise `400 WEBHOOK_URL_NOT_ALLOWED`. Loopback and private addresses (and plain `http`) are accepted only where `WEBHOOK_ALLOW_PRIVATE_TARGETS` is `true`, the development default; link-local and cloud-metadata addresses never are. The check is repeated at delivery, so a URL that stops qualifying fails its delivery attempts |
| `eventTypes` | at most 1000 characters; a comma-separated list such as `PAYMENT_STATUS_CHANGED,ORDER_CREATED`. Blank or `ALL` subscribes to every event |

## What a delivery looks like

operations-service POSTs a JSON body to `targetUrl`:

```json
{ "id": "5f0c…", "event": "PAYMENT_STATUS_CHANGED", "created": 1790000000, "payload": { "paymentId": "…", "paymentStatus": "CAPTURED", … } }
```

| Field | Meaning |
|---|---|
| `id` | The event's id — **the same for every delivery and every retry of this event**. Use it to drop duplicates: delivery is at-least-once, so a retry or a [replay](webhook-deliveries.md) can send an event you already handled |
| `event` | The event type (table below) |
| `created` | When the event was turned into a delivery, in epoch seconds |
| `payload` | The event's data |

**Verifying it.** Every attempt carries two headers, `X-PayFlo-Timestamp` (seconds since the epoch, taken when *that attempt* is sent) and `X-PayFlo-Signature`, the lowercase hex HMAC-SHA256, keyed with this config's secret (from the create or rotate response), of the string `<timestamp>.<raw request body>` — the timestamp, a dot, then the body **exactly as received**. Check three things, in this order:

1. The timestamp is within your tolerance of your own clock (5 minutes is the usual choice, either direction). Reject it otherwise: this is what stops a captured delivery being replayed later.
2. The signature matches, compared in constant time. Compute it over the bytes you received, not over a re-serialization of the parsed JSON — field order and spacing matter.
3. You haven't already handled this event: `X-PayFlo-Event-Id` repeats `id`, and delivery is at-least-once.

```python
import hmac, hashlib, time

def verify(secret: str, raw_body: bytes, signature: str, timestamp: str, tolerance: int = 300) -> bool:
    if abs(time.time() - int(timestamp)) > tolerance:
        return False
    expected = hmac.new(secret.encode(), timestamp.encode() + b"." + raw_body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, signature)
```

A retry is a new attempt with the **same body** and a **fresh timestamp and signature**, so a delivery that finally succeeds hours later still passes your check; only a *replay* of an old request fails it. `common-lib`'s `WebhookSignatures` is the reference implementation (`sign` and `verify`). Any `2xx` counts as delivered; anything else is retried after 1 min, 5 min, 30 min, 2 h, 8 h and 24 h before the event is dead-lettered. See the [webhook delivery flow](../architecture/flows/webhook-delivery.md).

| Event | Sent when |
|---|---|
| `ORDER_CREATED` | An order is created |
| `ORDER_CANCELLED` | An unpaid order is cancelled |
| `ORDER_EXPIRED` | An unpaid order passes its `expiresAt` |
| `PAYMENT_CREATED` | A payment attempt is recorded, whatever its first outcome |
| `PAYMENT_STATUS_CHANGED` | The simulated bank authorizes or declines a payment, a capture completes or is refused (`AUTHORIZED` with an `errorCode` of `CAPTURE_DECLINED`), or a payment times out. The payload has an `errorCode` when the payment has one |
| `PAYMENT_AUTHORIZATION_COMPENSATED` | A payment failed because the acquirer couldn't be reached |
| `REFUND_CREATED`, `REFUND_PROCESSED`, `REFUND_FAILED` | A refund is requested, completed by the bank, or declined |
| `SETTLEMENT_PROCESSED`, `SETTLEMENT_FAILED` | A payout completes or fails |

Deliveries can be listed, read and replayed: see [webhook deliveries](webhook-deliveries.md).
