# 0005. Publish events through a transactional outbox

**Status:** Accepted

## Context

When a payment changes state, operations-service has to find out, to notify the merchant. Sending to Kafka inside the request is a dual write: the database commit and the Kafka send can't be made atomic, so a crash between them either loses the event or announces a change that was rolled back.

## Decision

A service never calls Kafka from a request. It writes an `outbox_event` row — aggregate type and id, event type, JSON payload, `PENDING` — in the **same transaction** as the domain change. A scheduled `OutboxPoller` (every second, ShedLock-guarded) publishes pending rows to the topic for their aggregate type, keyed by merchant id — oldest first, in batches of 500 — and marks them `PUBLISHED` once Kafka acknowledges them, or `FAILED` after three attempts. payment-service and operations-service each have their own outbox.

## Consequences

- An event is published if and only if its change committed. Events are delivered at least once; consumers must tolerate a duplicate.
- Events arrive a second or two after the change — longer if changes are written faster than one poller can publish them; `payflo_outbox_pending` shows the backlog. Under the load test (~1,500 events/s: 274,547 outbox rows in one 3-minute run, with webhooks) it stays below ~1,500 and is empty shortly after the run.
- A row that fails three times is marked `FAILED` with its `last_error` and put back in the queue five minutes later, so a long Kafka outage delays events instead of stranding them.
- The outbox is one package in `common-lib`, imported by the two services that publish; each keeps its own `outbox_event` table, so no service reads another's.
