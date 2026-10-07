# Internal API

The service-to-service API. It is not part of the merchant contract and is **never routed by the gateway**.

- **Callers:** Feign clients, resolving each target by service name through Eureka (or a `*_SERVICE_URI` on Kubernetes), wrapped in a Resilience4j circuit breaker and retry.
- **Authentication:** a shared service token in the `X-Internal-Token` header (`INTERNAL_API_TOKEN`), checked by every service for any path under `/internal/` and sent by every Feign client; without it the answer is `401 UNAUTHORIZED`. This is a shared secret, not a per-service identity — any service holding the token can call any internal endpoint. Being unreachable from outside is still the first line of defence. See the [security model](../architecture/security-model.md#internal-api).
- **Authorization:** none. They accept arbitrary merchant and payment ids; the caller has already resolved the merchant.
- **Wire types** live in `common-lib`'s `dto` package.

## merchant-service

| Method | Path | Request | Response | Caller |
|---|---|---|---|---|
| `GET` | `/internal/api-keys/{keyId}` | — | `ApiKeyCacheEntry` — key id, both secret hashes, grace-period expiry, merchant id, environment, enabled | gateway, on a Redis cache miss |
| `POST` | `/internal/customers/find-or-create` | `FindOrCreateCustomerRequest { merchantId, email, name, phone }` | the customer's `UUID` | payment-service, creating an order |
| `GET` | `/internal/merchants/{merchantId}/webhook-targets?eventType=` | — | `List<WebhookTarget>` — config id, target URL, **decrypted** signing secret | operations-service, fanning out an event |
| `GET` | `/internal/merchants/{merchantId}/webhook-targets/{configId}` | — | `WebhookTarget` — the one config with its **decrypted** signing secret as it is now (`404` once the merchant has deleted it) | operations-service, signing each delivery attempt |
| `POST` | `/internal/audit` | `AuditEntryRequest { action, actorType, actor, merchantId?, targetType?, targetId?, details?, clientIp? }` | `204` | operations-service, recording the operator's settlement run in the [audit log](audit-log.md) that merchant-service owns, before running it |
| `GET` | `/internal/merchants/{merchantId}/status` | — | `MerchantStatus` | gateway, to refuse a suspended merchant (cached for 60 s) |
| `GET` | `/internal/merchants/active-ids` | — | `List<UUID>` | operations-service, starting settlement |
| `GET` | `/internal/merchants/{merchantId}/settlement-bank-details` | — | `SettlementBankDetails { accountNumber, ifsc, … }` | operations-service, paying out |

## payment-service

| Method | Path | Request | Response | Caller |
|---|---|---|---|---|
| `GET` | `/internal/payments/unsettled-captured?merchantId=&capturedBefore=&page=&size=` | — | `List<PaymentSettlementView>` — payment id, amount, **refunded amount**, currency | operations-service, settlement. Oldest first and paged (`size` up to 5000). Only `CAPTURED` and `PARTIALLY_REFUNDED` payments not yet paid out, captured before `capturedBefore` (the T+N hold), and with no refund still waiting on the bank |
| `POST` | `/internal/payments/mark-settled` | `List<UUID>` payment ids | `200` | operations-service, after a payout succeeds. Moves each through the state machine (`SETTLE`), with a transition-log row. Safe to repeat: a payment that isn't `CAPTURED` or `PARTIALLY_REFUNDED` any more is skipped |

## vault-service

| Method | Path | Request | Response | Caller |
|---|---|---|---|---|
| `POST` | `/internal/vault/charge` | `VaultChargeRequest { paymentId, merchantId, token, amount, methodDetails }` | `PaymentProcessorResponse` — `PENDING`, `SUCCESS` or `FAILURE`, with a `type` discriminator | payment-service's `CardPaymentAdapter` |

`/internal/vault/charge` decrypts the card behind the token and runs the mock card processor behind a thread-pool bulkhead with a 5-second timeout. An unknown or revoked token — or one created by a different merchant than `merchantId` — is `404`, so another merchant's token is indistinguishable from one that doesn't exist.
