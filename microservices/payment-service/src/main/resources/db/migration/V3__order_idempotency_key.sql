-- An order created with an X-Idempotency-Key now remembers the key, like a payment or a refund always did. Until now the key
-- for an order lived only in Redis, so a retry whose first response was lost while Redis was down could create a second
-- order. The unique index makes the database the backstop: orders created without a key leave the column null, and
-- PostgreSQL treats every null as different, so they are unaffected.
ALTER TABLE order_record ADD COLUMN idempotency_key character varying(100);

CREATE UNIQUE INDEX idx_order_merchant_idempotency ON order_record (merchant_id, idempotency_key);
