-- A delivery is signed when it is sent, over a fresh timestamp and the body, with the endpoint's secret as
-- merchant-service holds it at that moment. The secret is never stored here, so the event only remembers which
-- webhook config it was created for.
--
-- Rows created before this keep the signature that was computed when they were created (config_id is null on them),
-- which is why the column stays but may now be empty.
ALTER TABLE webhook_event ADD COLUMN config_id uuid;
ALTER TABLE webhook_event ALTER COLUMN signature DROP NOT NULL;
