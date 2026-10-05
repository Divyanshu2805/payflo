-- 1. Suspending a merchant from the admin API records why and when, and what to go back to on reactivation, so that
--    reactivating a merchant that was suspended before it passed KYC doesn't hand it ACTIVE.
ALTER TABLE merchant ADD COLUMN suspended_at timestamp(6) without time zone;
ALTER TABLE merchant ADD COLUMN suspension_reason character varying(255);
ALTER TABLE merchant ADD COLUMN status_before_suspension character varying(50);
ALTER TABLE merchant ADD CONSTRAINT merchant_status_before_suspension_check
    CHECK (status_before_suspension IS NULL OR status_before_suspension IN ('PENDING_KYC', 'ACTIVE'));

-- 2. The audit log: who did which sensitive thing to what, and when. Written in the same transaction as the change it
--    records, and append-only. The enum columns carry check constraints like every other enum column here: a new
--    AuditAction or AuditActorType constant widens them in a migration.
CREATE TABLE audit_log (
    id uuid NOT NULL,
    merchant_id uuid,
    actor_type character varying(20) NOT NULL,
    actor character varying(255),
    action character varying(40) NOT NULL,
    target_type character varying(40),
    target_id character varying(100),
    details jsonb,
    client_ip character varying(64),
    occurred_at timestamp(6) without time zone NOT NULL,
    CONSTRAINT audit_log_pkey PRIMARY KEY (id),
    CONSTRAINT audit_log_actor_type_check CHECK (actor_type IN ('USER', 'API_KEY', 'PLATFORM_ADMIN', 'SYSTEM')),
    CONSTRAINT audit_log_action_check CHECK (action IN (
        'SETTLEMENT_BANK_CHANGED', 'KYC_VERIFIED', 'PASSWORD_CHANGED',
        'API_KEY_CREATED', 'API_KEY_REVOKED', 'API_KEY_ROTATED',
        'WEBHOOK_CONFIG_CREATED', 'WEBHOOK_CONFIG_UPDATED', 'WEBHOOK_CONFIG_DELETED', 'WEBHOOK_SECRET_ROTATED',
        'USER_ADDED', 'USER_ROLE_CHANGED', 'USER_REMOVED',
        'MERCHANT_SUSPENDED', 'MERCHANT_REACTIVATED', 'SETTLEMENT_RUN_TRIGGERED'))
);

-- A merchant's own history, newest first; and the platform-wide one for the admin API.
CREATE INDEX idx_audit_log_merchant_occurred ON audit_log USING btree (merchant_id, occurred_at DESC);
CREATE INDEX idx_audit_log_occurred ON audit_log USING btree (occurred_at DESC);

-- Append-only, enforced by the database: a bug (or a careless statement) in the application can't rewrite or remove history.
CREATE FUNCTION audit_log_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_no_change BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_append_only();

CREATE TRIGGER audit_log_no_truncate BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit_log_append_only();
