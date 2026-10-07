# Admin API

What the platform operator can do: suspend and reactivate a merchant, run a settlement on demand, and read the [audit log](audit-log.md) across every merchant. **Services:** merchant-service (`AdminController`) and operations-service (`AdminSettlementController`) · **Prefix:** `/v1/admin`

This is **not** a merchant API. A merchant's JWT or API key never opens it — the gateway doesn't even look at them on these paths — and nothing a merchant can create, rotate or leak does either.

## Authentication

Every request carries `X-Admin-Key: <the platform admin key>`. The key is one shared secret, `app.security.admin-api-key` (`ADMIN_API_KEY`), known only to the gateway, so the operator holds it and merchants can't.

| Case | Answer |
|---|---|
| Right key | The request is forwarded with `X-Platform-Admin: true` and `X-Client-Ip` set by the gateway. The services refuse an admin request without that header (`403 ADMIN_REQUIRED`) |
| Missing or wrong key | `401 UNAUTHORIZED`, and the failure counts against the caller's address like any failed authentication — enough of them and that address is blocked for a while (`429`) |
| A merchant JWT or API key instead | `401`, the same as no key |
| `ADMIN_API_KEY` blank | `403 ADMIN_API_DISABLED`: the admin API is off |

The development default is `dev-admin-api-key-change-me`. In any shared environment set a long random `ADMIN_API_KEY`, and `ENFORCE_STRONG_SECRETS=true` makes the gateway refuse to start on the default. See [the security model](../architecture/security-model.md#the-platform-operator-the-admin-api).

```bash
curl -s -H 'X-Admin-Key: dev-admin-api-key-change-me' 'http://localhost:8080/v1/admin/merchants?status=ACTIVE'
```

## Merchants

| Method | Path | Request | Response | Notes |
|---|---|---|---|---|
| `GET` | `/v1/admin/merchants` | `?status=` (`PENDING_KYC`, `ACTIVE`, `SUSPENDED`), `?page=&size=` | `200` a page of `AdminMerchantResponse { id, name, email, businessName, status, suspendedAt?, suspensionReason?, createdAt }` | Newest first. It carries no payout account, PAN or GSTIN: the operator doesn't need them to act |
| `GET` | `/v1/admin/merchants/{merchantId}` | — | `200` `AdminMerchantResponse` | `404 MERCHANT_NOT_FOUND` |
| `POST` | `/v1/admin/merchants/{merchantId}/suspend` | `{ "reason": "…" }` (required, at most 255 characters) | `200` `AdminMerchantResponse` | See below. `400 MERCHANT_ALREADY_SUSPENDED` |
| `POST` | `/v1/admin/merchants/{merchantId}/reactivate` | optional `{ "reason": "…" }` | `200` `AdminMerchantResponse` | `400 MERCHANT_NOT_SUSPENDED` |

**Suspending** records the reason, the time and the status the merchant had (`ACTIVE` or `PENDING_KYC`), and takes effect **at once**: the gateway remembers each merchant's status for a minute, and the suspension writes that entry itself once it has committed, so the next request is refused with `403 MERCHANT_SUSPENDED` — API keys and dashboard sessions alike — and the merchant can't log in or refresh a token. Its payments in flight finish; it is left out of the next settlement run (only `ACTIVE` merchants are settled). The payout account and everything else are untouched.

**Reactivating** puts the merchant back to the status it had, so one suspended before it passed KYC goes back to waiting for KYC, not to `ACTIVE`. A merchant suspended some other way (the status edited in the database) has no recorded past and also goes back to `PENDING_KYC`.

Both are written to the [audit log](audit-log.md) in the same transaction as the change.

## Settlement on demand

| Method | Path | Request | Response | Notes |
|---|---|---|---|---|
| `POST` | `/v1/admin/settlements/run` | optional `{ "merchantId": "…" }` | `200` `{ startedAt, finishedAt, merchants, failedMerchants, settlementsCreated }` | Runs the settlement the nightly job runs, now: every `ACTIVE` merchant, or just the one named |

It is the nightly run started by hand — same code, same lock (`operations-service-settlement-engine`) — so the two can never overlap: while one is running, a second answers `409 SETTLEMENT_RUN_IN_PROGRESS`. `merchants` is how many were tried, `failedMerchants` how many of those failed (their payments are simply picked up by the next run), and `settlementsCreated` how many payouts it started. The bank answers a few seconds later: watch `GET /v1/settlements` as the merchant, or [the settlement flow](../architecture/flows/settlement.md).

- A merchant that is unknown, still waiting for KYC or suspended is `400 MERCHANT_NOT_ACTIVE`.
- The run is **audited before it starts**; if merchant-service can't record it, nothing runs (`503 AUDIT_LOG_UNAVAILABLE`). There is no unaudited admin action.
- The request runs the settlement in-line, which for a handful of merchants is seconds. For thousands, leave it to the nightly job.

Settling requires each merchant to have a payout account and `ACTIVE` status, as always; a merchant without one is skipped.

## Related

- [Audit log](audit-log.md) — what the operator's actions leave behind.
- [Settlements](settlements.md) and the [settlement flow](../architecture/flows/settlement.md).
- [Security model](../architecture/security-model.md) — why the admin key is the gateway's alone.
