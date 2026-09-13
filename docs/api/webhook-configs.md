# Webhook Configs

The endpoints a merchant wants events delivered to. **Service:** merchant-service · **Controller:** `WebhookConfigController` (`/v1/merchants/webhooks`)

| Method | Path | Request | Response | Notes |
|---|---|---|---|---|
| `POST` | `/v1/merchants/webhooks` | `UpdateWebhookConfigRequest { targetUrl, eventTypes? }` | `200` `WebhookConfigResponse { id, targetUrl, webhookSecret, enabled, eventTypes }` | The server generates the signing secret. **`webhookSecret` appears only in this response**; it is stored AES-encrypted. |
| `GET` | `/v1/merchants/webhooks` | — | `200` `List<WebhookConfigResponse>` | Without `webhookSecret`. |
| `GET` | `/v1/merchants/webhooks/{id}` | — | `200` `WebhookConfigResponse` | `404` for an unknown id or another merchant's config. |
| `PUT` | `/v1/merchants/webhooks/{id}` | `UpdateWebhookConfigRequest` | `200` `WebhookConfigResponse` | Changes `targetUrl` and `eventTypes`. The secret can't be changed — delete and recreate the config to get a new one. |
| `DELETE` | `/v1/merchants/webhooks/{id}` | — | `204` | |

| Field | Constraint |
|---|---|
| `targetUrl` | required, at most 500 characters, must start with `http://` or `https://` |
| `eventTypes` | at most 1000 characters; a comma-separated list such as `PAYMENT_STATUS_CHANGED,ORDER_CREATED`. Blank or `ALL` subscribes to every event |

## What a delivery looks like

operations-service POSTs the event's payload as JSON to `targetUrl`, with an HMAC-SHA256 signature of the body — computed with this config's secret — in the `X-PayFlo-Signature` header. Recompute the signature with the secret from the create response and compare before trusting a delivery. Any `2xx` counts as delivered; anything else is retried after 1 min, 5 min, 30 min, 2 h, 8 h and 24 h before the event is dead-lettered. See the [webhook delivery flow](../architecture/flows/webhook-delivery.md).

| Event | Sent when |
|---|---|
| `ORDER_CREATED` | An order is created |
| `PAYMENT_CREATED` | A payment attempt is recorded, whatever its first outcome |
| `PAYMENT_STATUS_CHANGED` | The simulated bank authorizes or declines a payment, or a capture completes |
| `PAYMENT_AUTHORIZATION_COMPENSATED` | A payment failed because the acquirer couldn't be reached |
| `SETTLEMENT_PROCESSED`, `SETTLEMENT_FAILED` | A nightly payout completes or fails |

There is no endpoint to list deliveries or replay a dead-lettered one yet.
