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
| `GET` | `/internal/merchants/{merchantId}/status` | — | `MerchantStatus` | gateway, to refuse a suspended merchant (cached for 60 s) |
| `GET` | `/internal/merchants/active-ids` | — | `List<UUID>` | operations-service, starting settlement |
| `GET` | `/internal/merchants/{merchantId}/settlement-bank-details` | — | `SettlementBankDetails { accountNumber, ifsc, … }` | operations-service, paying out |

## payment-service

| Method | Path | Request | Response | Caller |
|---|---|---|---|---|
| `GET` | `/internal/payments/unsettled-captured?merchantId=` | — | `List<PaymentSettlementView>` — payment id, amount, currency, … | operations-service, settlement |
| `POST` | `/internal/payments/mark-settled` | `List<UUID>` payment ids | `200` | operations-service, after a payout succeeds. Sets `SETTLED` directly, without the state machine |

## vault-service

| Method | Path | Request | Response | Caller |
|---|---|---|---|---|
| `POST` | `/internal/vault/charge` | `VaultChargeRequest { paymentId, merchantId, token, amount, methodDetails }` | `PaymentProcessorResponse` — `PENDING`, `SUCCESS` or `FAILURE`, with a `type` discriminator | payment-service's `CardPaymentAdapter` |

`/internal/vault/charge` decrypts the card behind the token and runs the mock card processor behind a thread-pool bulkhead with a 5-second timeout. An unknown or revoked token — or one created by a different merchant than `merchantId` — is `404`, so another merchant's token is indistinguishable from one that doesn't exist.
