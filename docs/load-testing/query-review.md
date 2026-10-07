# Query and Index Review

A review of what the database does under the [load test](running.md), done with `pg_stat_statements` (the time and call count of every statement) and `EXPLAIN (ANALYZE, BUFFERS)` on the slow or frequent ones, against the ~2 million order and payment rows the earlier runs left behind. It found four things worth changing and a list of queries that are fine as they are.

## How to repeat it

`services.docker-compose.yaml` starts PostgreSQL with `shared_preload_libraries=pg_stat_statements`. On a database that was created before that, set it once and restart (`ALTER SYSTEM SET shared_preload_libraries = 'pg_stat_statements'`, then restart the container), and create the extension in each database:

```sql
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
SELECT pg_stat_statements_reset();   -- just before the run
```

After a run, the statements that took the most database time (the extension is cluster-wide; each row says which database it ran in):

```sql
SELECT d.datname, calls, round(total_exec_time::numeric / 1000, 1) AS total_s,
       round(mean_exec_time::numeric, 3) AS mean_ms, left(regexp_replace(query, '\s+', ' ', 'g'), 120) AS query
FROM pg_stat_statements s JOIN pg_database d ON d.oid = s.dbid
WHERE d.datname LIKE 'payflo%' ORDER BY total_exec_time DESC LIMIT 25;
```

Divide `calls` by the number of payments the run made: a statement that runs more often per payment than the code seems to ask for is the interesting kind. Then `EXPLAIN (ANALYZE, BUFFERS)` the ones that are slow per call, inside `BEGIN; … ROLLBACK;` if the experiment creates or drops an index.

## What it found

The first run was 70,880 payments in 3 minutes with every merchant subscribed to webhooks.

| # | Finding | Evidence | Change |
|---|---|---|---|
| 1 | **Every outbox row was written twice.** An `INSERT` was followed by an `UPDATE` of the whole row in the same flush, for every event | `update outbox_event set aggregate_id=…` ran 223,890 times (3.2 per payment, 36 s of database time) for 223,891 inserts | The payload held a `UUID` or an enum. Hibernate keeps a copy of a JSON column made by writing it out and reading it back, and compares the value with that copy at flush: a `UUID` is a `String` after the round trip, so the row looked changed. `OutboxEventPublisher` now stores the payload as JSON gives it back (the same JSON goes out). The updates are gone |
| 2 | **Every payment was written three times at initiation** instead of twice: inserted as `CREATED`, then updated to `AUTHORIZING` in the same flush | `update payment` ran 212,640 times for 70,880 payments | The first transition is applied to the payment *before* it is saved, so it is inserted already `AUTHORIZING`; its log entry is saved after it. 2.0 updates per payment now |
| 3 | **The order-expiry sweeper scanned the whole order table every minute** | The statement averaged 115 ms (620 ms at worst) and `EXPLAIN` showed a parallel sequential scan of 2.2 million rows, 72,000 buffers, to find *no* rows | `V4__index_review.sql`: a partial index on `expires_at` for unpaid orders only (`WHERE order_status IN ('CREATED','ATTEMPTED')`), replacing `(order_status, expires_at)`, which nearly every order (all `PAID`) matched. 655 ms → 0.05 ms, and a paid order adds no index entry |
| 4 | **Three indexes did nothing another didn't already do**, and every write maintained them | `idx_order_merchant_id` and `idx_payment_merchant_id` are the first column of a longer index; `idx_order_id_merchant_id` finds the same single row the primary key does (28 + 20 + 151 MB) | Dropped in the same migration. Order lookups by id now use the primary key (0.9 ms cold, `EXPLAIN` checked) |

The effect on the load test is in [results](results.md#the-database-work-per-payment).

## Checked and left alone

`EXPLAIN (ANALYZE, BUFFERS)` on the production queries, with a 40,000-payment merchant:

| Query | Plan | Time |
|---|---|---|
| Simulator: oldest `AUTHORIZING` payments | `idx_payment_status_created_at` | 0.05 ms (5 ms when it returns 500) |
| Outbox poller: oldest `PENDING` rows | `idx_outbox_event_status_created_at` | 0.1 ms (9 ms for 500) |
| `GET /v1/payments`, newest first | `idx_payment_merchant_created`, backwards | 0.2 ms |
| `GET /v1/payments?status=FAILED` | the same index, filtering status | 1.7 ms (163 rows skipped for 21) |
| `GET /v1/orders`, newest first | `idx_order_merchant_created` | 0.3 ms |
| Settlement: a merchant's settleable payments | `idx_payment_merchant_created`, anti-join on refunds | 3.7 ms for 1,000 |
| Receipt check on order creation | `idx_order_merchant_receipt` (unique) | 0.09 ms |
| Is there a live payment for this order | `idx_payment_order_id` | 0.07 ms |
| Webhook gauge: oldest unattempted event | `idx_webhook_event_status_next_retry`, first match | 0.5 ms |

## Found, not changed

- **The analytics queries read the table, not an index.** Revenue for the last 7 days of a merchant with 18,500 captured payments took 284 ms, and 366 days of outcomes (40,000 rows) took 414 ms, mostly reading ~15,000 heap pages cold: a merchant's payments are scattered among 198 merchants' rows, so each page holds a row or two of theirs. Covering indexes (`INCLUDE (amount_units)`, `INCLUDE (status)`) would allow index-only scans, but they widen two indexes that every payment write maintains, for an endpoint that is not on the load test's path, and recent pages are being updated, so the visibility map wouldn't cover them anyway. A daily rollup table is the real answer if the dashboard needs to be fast at this size.
- **`UPDATE`s of `payment` and `order_record` are never HOT.** `status` and `captured_at` are indexed, and an indexed column changing forces every index to take a new entry. Removing the status index would need a different way to find payments waiting for the bank.
