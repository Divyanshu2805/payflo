# Webhook Deliveries

What happened to each webhook sent to a merchant's endpoints, and a way to send one again. **Service:** operations-service · **Controller:** `WebhookDeliveryController` (`/v1/webhook-deliveries`)

A *delivery* is one event sent to one endpoint. The endpoints themselves are managed under [webhook configs](webhook-configs.md).

| Method | Path | Request | Response | Notes |
|---|---|---|---|---|
| `GET` | `/v1/webhook-deliveries` | `?status=&page=&size=` | `200` a page of `WebhookDeliveryResponse`, newest first | `status`: `PENDING`, `DELIVERED`, `FAILED`, `DEAD`. |
| `GET` | `/v1/webhook-deliveries/{deliveryId}` | — | `200` `WebhookDeliveryResponse { id, eventId, eventType, targetUrl, status, attempts, nextRetryAt, lastAttemptAt, lastResponseCode, lastResponseBody, deliveredAt, payload, createdAt }` | `404 WEBHOOKDELIVERY_NOT_FOUND` for an unknown or another merchant's delivery. The signature isn't included. |
| `POST` | `/v1/webhook-deliveries/{deliveryId}/replay` | — | `200` the delivery, now `PENDING` | Sends it again from the start, due immediately, with a fresh set of attempts. |

## Statuses

| `status` | Meaning |
|---|---|
| `PENDING` | Created, not yet attempted |
| `DELIVERED` | The endpoint answered `2xx` |
| `FAILED` | An attempt failed; `nextRetryAt` is when the next one is due |
| `DEAD` | Seven attempts failed. It stays here until replayed |

## Replay

Replay works on a `DEAD`, `FAILED` or `DELIVERED` delivery (a `PENDING` one is already queued: `400 WEBHOOK_DELIVERY_PENDING`). The **same body** is sent as the first time, signed afresh (new `X-PayFlo-Timestamp` and signature), with the same `X-PayFlo-Event-Id`, so a receiver that de-duplicates on the event id will recognise it. Replaying a `DEAD` one also marks its dead-letter entry replayed.

## Related

- [Webhook configs](webhook-configs.md) — the signature and the body that is sent.
- [Webhook delivery flow](../architecture/flows/webhook-delivery.md).
