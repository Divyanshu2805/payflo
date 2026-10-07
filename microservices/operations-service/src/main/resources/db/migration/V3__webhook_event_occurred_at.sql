-- When the change a webhook reports happened (the payment-service or operations-service outbox row's creation time).
-- Delivery latency, the number the 99%-within-30-seconds webhook SLA is measured on, runs from here to the 2xx answer.
-- Null on rows from before this existed: their deliveries are simply left out of the latency metric.
ALTER TABLE webhook_event ADD COLUMN event_occurred_at timestamp(6) without time zone;
