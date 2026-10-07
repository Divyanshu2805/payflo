-- Query and index review under load (docs/load-testing/query-review.md).

-- 1. The order-expiry sweeper ran a parallel sequential scan of the whole order table every minute (655 ms over
--    2.2 million rows, evicting hot pages from the cache), because (order_status, expires_at) is a poor index for "unpaid
--    and past due": nearly every order is PAID. Only unpaid orders can expire, so index only those. Paid orders leave
--    the index for good, and an order moving to PAID adds no index entry. The sweeper's query now takes 0.05 ms.
CREATE INDEX idx_order_unpaid_expires_at ON order_record (expires_at) WHERE order_status IN ('CREATED', 'ATTEMPTED');
DROP INDEX idx_order_status_expires_at;

-- 2. Indexes another index already serves. Every insert and every status change pays to maintain each one.
--    (merchant_id) is the first column of idx_order_merchant_created, idx_order_merchant_receipt and
--    idx_order_merchant_idempotency; (id, merchant_id) finds the same single row the primary key does, and the
--    primary key is as fast for it once merchant_id is only a filter on that row.
DROP INDEX idx_order_merchant_id;
DROP INDEX idx_order_id_merchant_id;
--    payment: (merchant_id) is the first column of idx_payment_merchant_created and idx_payment_merchant_captured.
DROP INDEX idx_payment_merchant_id;
