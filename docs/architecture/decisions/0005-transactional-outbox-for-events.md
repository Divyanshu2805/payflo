# 0005. Publish events through a transactional outbox

**Status:** Accepted

## Context

When a payment changes state, operations-service has to find out, to notify the merchant. Sending to Kafka inside the request is a dual write: the database commit and the Kafka send can't be made atomic, so a crash between them either loses the event or announces a change that was rolled back.

## Decision

A service never calls Kafka from a request. It writes an `outbox_event` row — aggregate type and id, event type, JSON payload, `PENDING` — in the **same transaction** as the domain change. A scheduled `OutboxPoller` (every 5 seconds, ShedLock-guarded) publishes pending rows to the topic for their aggregate type, keyed by merchant id — oldest first, in batches of 500 — and marks them `PUBLISHED` once Kafka acknowledges them, or `FAILED` after three attempts. payment-service and operations-service each have their own outbox.

## Consequences

- An event is published if and only if its change committed. Events are delivered at least once; consumers must tolerate a duplicate.
- Events arrive a second or two after the change — longer if changes are written faster than one poller can publish them; `payflo_outbox_pending` shows the backlog. Under the load test (~1,250 events/s) it stays below ~1,600.
- A row that fails three times is never retried automatically; it stays visible with its `last_error`.
- The outbox classes are duplicated in the two services rather than shared.
