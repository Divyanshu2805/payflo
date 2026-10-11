-- Two columns nothing ever wrote. api_key.last_used_at: the gateway authenticates from a cache, so there is no write per
-- request to set it. customer.deleted_at: customers are never deleted.
ALTER TABLE api_key DROP COLUMN last_used_at;
ALTER TABLE customer DROP COLUMN deleted_at;
