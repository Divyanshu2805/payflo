# 0005. Publish events through a transactional outbox

**Status:** Accepted

## Context

When a payment changes state, operations-service has to find out, to notify the merchant. Sending to Kafka inside the request is a dual write: the database commit and the Kafka send can't be made atomic, so a crash between them either loses the event or announces a change that was rolled back.

## Decision

A service never calls Kafka from a request. It writes an `outbox_event` row — aggregate type and id, event type, JSON payload, `PENDING` — in the **same transaction** as the domain change. A scheduled `OutboxPoller` (every 5 seconds, ShedLock-guarded) publishes pending rows to the topic for their aggregate type, keyed by merchant id, and marks them `PUBLISHED`, or `FAILED` after three attempts. payment-service and operations-service each have their own outbox.

## Consequences

- An event is published if and only if its change committed. Events are delivered at least once; consumers must tolerate a duplicate.
- Events arrive up to a few seconds after the change.
- A row that fails three times is never retried automatically; it stays visible with its `last_error`.
- The outbox classes are duplicated in the two services rather than shared.
