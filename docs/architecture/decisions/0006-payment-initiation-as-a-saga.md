# 0006. Payment initiation as a saga

**Status:** Accepted

## Context

Initiating a payment has to lock the order, create the payment and move it to `AUTHORIZING`, call the acquirer (for a card, over the network to vault-service), and record the result. Doing all of that in one database transaction would hold a connection and row locks for the whole remote call, so one slow acquirer could exhaust the connection pool.

## Decision

`PaymentAuthorizationRecorder` splits initiation into steps, with no transaction open during the remote call:

1. `recordPayment` — one transaction: lock the order, check it is payable, create the payment, fire `AUTHORIZE_ATTEMPT`.
2. Call the payment adapter — no transaction.
3. `applyGatewayResult` — a second transaction: record the processor reference or the failure, and write the outbox event.
4. If step 2 throws, `compensateAuthorizationFailure` moves the payment to `FAILED` and publishes `PAYMENT_AUTHORIZATION_COMPENSATED`.

A repeated request with the same `X-Idempotency-Key` returns the existing attempt. Order creation follows the same rule: the customer is resolved over the network before the order's transaction opens.

## Consequences

- Database connections are never held across a network call on the payment path.
- Between steps 1 and 3 the payment is visibly `AUTHORIZING`; a crash in that window leaves it there until the bank callback simulator resolves it.
- Settlement does not follow this pattern yet: it calls other services inside its transaction — see [known gaps](../../gaps.md).
