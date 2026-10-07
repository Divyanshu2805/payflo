# Audit Log

Who did which sensitive thing to a merchant's account, and when. **Service:** merchant-service · **Controllers:** `AuditLogController` (`/v1/merchants/audit-log`) and `AdminController` (`/v1/admin/audit-log`)

## Reading it

| Method | Path | Who | Response |
|---|---|---|---|
| `GET` | `/v1/merchants/audit-log` | A dashboard login that is `OWNER` or `ADMIN`; `?action=`, `?page=&size=` | `200` a page of `AuditLogResponse`, newest first, **this merchant's entries only** |
| `GET` | `/v1/admin/audit-log` | The platform operator ([admin API](admin.md)); `?merchantId=`, `?action=`, `?page=&size=` | The same, across every merchant, or one |

A `TEAM` member, or an API key (a merchant's backend, which may be leaked), is `403`: the log names the team's emails and what each did.

```json
{ "id": "…", "merchantId": "…", "actorType": "USER", "actor": "owner@example.com",
  "action": "SETTLEMENT_BANK_CHANGED", "targetType": "MERCHANT", "targetId": "…",
  "details": { "account": "XXXXXXXX9012", "ifsc": "HDFC0001234" },
  "clientIp": "203.0.113.7", "occurredAt": "2026-10-06T19:02:11" }
```

## What is recorded

| `action` | When | `details` |
|---|---|---|
| `SETTLEMENT_BANK_CHANGED` | The payout account is set or changed | the **masked** account number and the IFSC |
| `KYC_VERIFIED` | A merchant passes KYC and becomes `ACTIVE` | — |
| `PASSWORD_CHANGED` | A dashboard user changes their password | — (never the password) |
| `API_KEY_CREATED`, `API_KEY_REVOKED`, `API_KEY_ROTATED` | An API key is created, revoked or rotated | the key's public id, its environment or the grace period (never the secret) |
| `WEBHOOK_CONFIG_CREATED`, `WEBHOOK_CONFIG_UPDATED`, `WEBHOOK_CONFIG_DELETED` | A webhook endpoint changes | the target **host** (a URL can carry a token), and for an update what changed (`enabled`, `eventTypesChanged`) |
| `WEBHOOK_SECRET_ROTATED` | A signing secret is rotated | — (never the secret) |
| `USER_ADDED`, `USER_ROLE_CHANGED`, `USER_REMOVED` | The owner changes who can log in | the user's email and role (and the old and new role) |
| `MERCHANT_SUSPENDED`, `MERCHANT_REACTIVATED` | The operator suspends or reactivates a merchant | the reason, and the status before or restored |
| `SETTLEMENT_RUN_TRIGGERED` | The operator runs a settlement | `scope`: `ALL_ACTIVE_MERCHANTS` or `ONE_MERCHANT` |

`actorType` is `USER` (a dashboard login; `actor` is their email), `API_KEY` (`actor` is the key's public id), `PLATFORM_ADMIN` (the operator, `actor` is `platform-admin`) or `SYSTEM`. The actor and `clientIp` are read from the request the gateway authenticated, never from anything the caller says about themselves. `clientIp` is the address the gateway saw, or the one in `CLIENT_IP_HEADER` behind a proxy.

## Guarantees

- **Atomic with the change.** An entry is written in the same transaction as what it describes: a change that rolls back leaves no entry, and a committed one can't miss it. A change the caller wasn't allowed to make (wrong role, wrong password) leaves nothing, because nothing happened.
- **Append-only.** The table refuses `UPDATE`, `DELETE` and `TRUNCATE` with a database trigger, so a bug (or a careless statement) in the application can't rewrite or remove history. It is not tamper-proof against someone who owns the database: that would take hash-chaining or an external sink.
- **No secrets.** Callers record facts, never values: a masked account number, a host, a key's public id. As a net under that, a detail whose name looks like a secret (`secret`, `password`, `token`, `hash`, `cvv`, `pan`) is replaced with `[redacted]` before it is stored.
- **No unaudited admin action.** The operator's settlement run is recorded first, through `POST /internal/audit` on merchant-service, and doesn't start if that fails.

## Not recorded

Reads, logins and failed logins, profile edits other than the payout account, payment and refund activity (those have the payment transition log and events), and the operator's reads. There is no retention policy and no export: the table only grows.

## Related

- [Admin API](admin.md) — the operator's actions.
- [merchant-service data model](../schema/merchant-service.md#audit_log).
