-- Baseline of the payflo_operations schema, taken from the database Hibernate's ddl-auto had built, with the enum check
-- constraints widened to match the enums. Flyway marks this version as already applied on a database that
-- existed before Flyway (baseline-on-migrate), and runs it on an empty one.

CREATE TABLE dlq_event (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    updated_at timestamp(6) without time zone,
    updated_by character varying(255),
    final_error character varying(1000),
    merchant_id uuid NOT NULL,
    moved_at timestamp(6) without time zone,
    payload jsonb NOT NULL,
    replayed_at timestamp(6) without time zone,
    webhook_event_id uuid
);

CREATE TABLE outbox_event (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    updated_at timestamp(6) without time zone,
    updated_by character varying(255),
    aggregate_id uuid NOT NULL,
    aggregate_type character varying(255) NOT NULL,
    attempts integer NOT NULL,
    event_type character varying(50) NOT NULL,
    last_error character varying(1000),
    payload jsonb NOT NULL,
    published_at timestamp(6) without time zone,
    status character varying(255) NOT NULL,
    CONSTRAINT outbox_event_aggregate_type_check CHECK (((aggregate_type)::text = ANY ((ARRAY['PAYMENT'::character varying, 'ORDER'::character varying, 'REFUND'::character varying, 'SETTLEMENT'::character varying])::text[]))),
    CONSTRAINT outbox_event_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PUBLISHED'::character varying, 'FAILED'::character varying])::text[])))
);

CREATE TABLE settlement (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    updated_at timestamp(6) without time zone,
    updated_by character varying(255),
    bank_reference character varying(50),
    failure_reason character varying(255),
    fee_amount_units integer NOT NULL,
    fee_amount_currency character varying(255) NOT NULL,
    gross_amount_units integer NOT NULL,
    gross_amount_currency character varying(255) NOT NULL,
    gst_amount_units integer NOT NULL,
    gst_amount_currency character varying(255) NOT NULL,
    merchant_id uuid NOT NULL,
    net_amount_units integer NOT NULL,
    net_amount_currency character varying(255) NOT NULL,
    processed_at timestamp(6) without time zone,
    refund_amount_units integer NOT NULL,
    refund_amount_currency character varying(255) NOT NULL,
    status character varying(20) NOT NULL,
    payments_settled_at timestamp(6) without time zone,
    CONSTRAINT settlement_status_check CHECK (((status)::text = ANY ((ARRAY['INITIATED'::character varying, 'TRANSFER_PENDING'::character varying, 'PROCESSED'::character varying, 'FAILED'::character varying])::text[])))
);

CREATE TABLE settlement_payment (
    payment_id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    updated_at timestamp(6) without time zone,
    updated_by character varying(255),
    settlement_id uuid NOT NULL
);

CREATE TABLE webhook_event (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    updated_at timestamp(6) without time zone,
    updated_by character varying(255),
    attempts integer NOT NULL,
    delivered_at timestamp(6) without time zone,
    event_type character varying(100) NOT NULL,
    last_attempt_at timestamp(6) without time zone,
    last_response_body character varying(1000),
    last_response_code integer,
    merchant_id uuid NOT NULL,
    next_retry_at timestamp(6) without time zone,
    payload jsonb,
    signature character varying(255) NOT NULL,
    status character varying(255) NOT NULL,
    target_url character varying(255) NOT NULL,
    event_id character varying(100),
    request_body text,
    CONSTRAINT webhook_event_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'DELIVERED'::character varying, 'FAILED'::character varying, 'DEAD'::character varying])::text[])))
);

ALTER TABLE ONLY dlq_event
    ADD CONSTRAINT dlq_event_pkey PRIMARY KEY (id);

ALTER TABLE ONLY outbox_event
    ADD CONSTRAINT outbox_event_pkey PRIMARY KEY (id);

ALTER TABLE ONLY settlement_payment
    ADD CONSTRAINT settlement_payment_pkey PRIMARY KEY (payment_id, settlement_id);

ALTER TABLE ONLY settlement
    ADD CONSTRAINT settlement_pkey PRIMARY KEY (id);

ALTER TABLE ONLY dlq_event
    ADD CONSTRAINT uk2rnmlgvax847c3ws5yhtp1hgs UNIQUE (webhook_event_id);

ALTER TABLE ONLY webhook_event
    ADD CONSTRAINT webhook_event_pkey PRIMARY KEY (id);

CREATE INDEX idx_outbox_event_status_created_at ON outbox_event USING btree (status, created_at);

CREATE INDEX idx_webhook_event_merchant_created ON webhook_event USING btree (merchant_id, created_at);

CREATE INDEX idx_webhook_event_status_next_retry ON webhook_event USING btree (status, next_retry_at);

ALTER TABLE ONLY settlement_payment
    ADD CONSTRAINT fk11x8ihxqap99rjvw65eitmmd FOREIGN KEY (settlement_id) REFERENCES settlement(id);

ALTER TABLE ONLY dlq_event
    ADD CONSTRAINT fkqv72xi3tag231hjexxkni4q6a FOREIGN KEY (webhook_event_id) REFERENCES webhook_event(id);
