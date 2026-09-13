# 0007. A validated, logged payment state machine

**Status:** Accepted

## Context

A payment's status drives money movement: capturing an unauthorized payment, or settling one twice, is a real loss. A free-form status column that any code can set makes those mistakes easy and leaves no history once the column is overwritten.

## Decision

`PaymentStateMachine` holds the complete table of allowed `(PaymentStatus, PaymentEvent) → PaymentStatus` transitions. Status changes go through `PaymentTransitionService.apply`, which looks up the next status — throwing `InvalidStateTransitionException` (`409 INVALID_STATE_TRANSITION`) for an undefined pair — sets it, and writes a `payment_transition_log` row with the from-status, event, to-status, actor and time. In-flight states (`AUTHORIZING`, `CAPTURING`) are explicit, so a request already sent to the acquirer is distinguishable from one never attempted, and a failed capture returns to `AUTHORIZED` so it can be retried.

## Consequences

- An illegal move is rejected before anything happens, and every legal one is on record even though `payment.status` is overwritten.
- Adding a status or event means updating the enum in `common-lib` and the table.
- The table defines transitions nothing fires yet (cancel, capture timeout, refunds, settle). Settlement sets `SETTLED` directly, bypassing the machine and the log — see [known gaps](../../known-gaps/not-yet-built.md#settlement).
- `actor` is always `SYSTEM` today.
