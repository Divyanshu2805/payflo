-- Baseline of the payflo_payment schema, taken from the database Hibernate's ddl-auto had built, with the enum check
-- constraints widened to match the enums. Flyway marks this version as already applied on a database that
-- existed before Flyway (baseline-on-migrate), and runs it on an empty one.

CREATE TABLE order_record (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    updated_at timestamp(6) without time zone,
    updated_by character varying(255),
    amount_units integer,
    currency character varying(255),
    attempts integer NOT NULL,
    customer_id uuid,
    expires_at timestamp(6) without time zone NOT NULL,
    merchant_id uuid NOT NULL,
    notes jsonb,
    order_status character varying(20) NOT NULL,
    receipt character varying(100),
    CONSTRAINT order_record_order_status_check CHECK (((order_status)::text = ANY ((ARRAY['CREATED'::character varying, 'ATTEMPTED'::character varying, 'PAID'::character varying, 'CANCELLED'::character varying, 'EXPIRED'::character varying])::text[])))
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

CREATE TABLE payment (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    updated_at timestamp(6) without time zone,
    updated_by character varying(255),
    amount_units integer,
    currency character varying(255),
    authorized_at timestamp(6) without time zone,
    bank_reference character varying(100),
    captured_at timestamp(6) without time zone,
    error_code character varying(100),
    error_description character varying(255),
    failed_at timestamp(6) without time zone,
    idempotency_key character varying(100) NOT NULL,
    merchant_id uuid NOT NULL,
    method smallint NOT NULL,
    method_details jsonb,
    processor_reference character varying(100),
    refunded_at timestamp(6) without time zone,
    settled_at timestamp(6) without time zone,
    status character varying(30) NOT NULL,
    order_id uuid NOT NULL,
    CONSTRAINT payment_method_check CHECK (((method >= 0) AND (method <= 3))),
    CONSTRAINT payment_status_check CHECK (((status)::text = ANY ((ARRAY['CREATED'::character varying, 'AUTHORIZING'::character varying, 'AUTHORIZED'::character varying, 'CAPTURING'::character varying, 'CAPTURED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying, 'REFUNDED'::character varying, 'PARTIALLY_REFUNDED'::character varying, 'SETTLED'::character varying, 'AUTH_EXPIRED'::character varying])::text[])))
);

CREATE TABLE payment_transition_log (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    updated_at timestamp(6) without time zone,
    updated_by character varying(255),
    actor character varying(100),
    event character varying(30) NOT NULL,
    from_status character varying(30),
    occurred_at timestamp(6) without time zone NOT NULL,
    to_status character varying(30) NOT NULL,
    payment_id uuid NOT NULL,
    CONSTRAINT payment_transition_log_actor_check CHECK (((actor)::text = ANY ((ARRAY['CUSTOMER'::character varying, 'MERCHANT'::character varying, 'SYSTEM'::character varying])::text[]))),
    CONSTRAINT payment_transition_log_event_check CHECK (((event)::text = ANY ((ARRAY['AUTHORIZE_ATTEMPT'::character varying, 'AUTHORIZE_SUCCESS'::character varying, 'AUTHORIZE_FAIL'::character varying, 'CAPTURE_REQUEST'::character varying, 'CAPTURE_SUCCESS'::character varying, 'CAPTURE_FAIL'::character varying, 'REFUND_INIT'::character varying, 'REFUND_COMPLETE'::character varying, 'SETTLE'::character varying, 'CANCEL'::character varying, 'CAPTURE_TIMEOUT'::character varying, 'REFUND_FAIL'::character varying])::text[]))),
    CONSTRAINT payment_transition_log_from_status_check CHECK (((from_status)::text = ANY ((ARRAY['CREATED'::character varying, 'AUTHORIZING'::character varying, 'AUTHORIZED'::character varying, 'CAPTURING'::character varying, 'CAPTURED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying, 'REFUNDED'::character varying, 'PARTIALLY_REFUNDED'::character varying, 'SETTLED'::character varying, 'AUTH_EXPIRED'::character varying])::text[]))),
    CONSTRAINT payment_transition_log_to_status_check CHECK (((to_status)::text = ANY ((ARRAY['CREATED'::character varying, 'AUTHORIZING'::character varying, 'AUTHORIZED'::character varying, 'CAPTURING'::character varying, 'CAPTURED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying, 'REFUNDED'::character varying, 'PARTIALLY_REFUNDED'::character varying, 'SETTLED'::character varying, 'AUTH_EXPIRED'::character varying])::text[])))
);

CREATE TABLE refund (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    updated_at timestamp(6) without time zone,
    updated_by character varying(255),
    amount_units integer,
    currency character varying(255),
    bank_reference character varying(100),
    error_code character varying(100),
    error_description character varying(500),
    merchant_id uuid NOT NULL,
    notes jsonb,
    processed_at timestamp(6) without time zone,
    status character varying(255) NOT NULL,
    payment_id uuid NOT NULL,
    idempotency_key character varying(100),
    CONSTRAINT refund_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PROCESSING'::character varying, 'PROCESSED'::character varying, 'FAILED'::character varying])::text[])))
);

ALTER TABLE ONLY order_record
    ADD CONSTRAINT idx_order_merchant_receipt UNIQUE (merchant_id, receipt);

ALTER TABLE ONLY payment
    ADD CONSTRAINT idx_payment_merchant_idempotency UNIQUE (merchant_id, idempotency_key);

ALTER TABLE ONLY refund
    ADD CONSTRAINT idx_refund_merchant_idempotency UNIQUE (merchant_id, idempotency_key);

ALTER TABLE ONLY order_record
    ADD CONSTRAINT order_record_pkey PRIMARY KEY (id);

ALTER TABLE ONLY outbox_event
    ADD CONSTRAINT outbox_event_pkey PRIMARY KEY (id);

ALTER TABLE ONLY payment
    ADD CONSTRAINT payment_pkey PRIMARY KEY (id);

ALTER TABLE ONLY payment_transition_log
    ADD CONSTRAINT payment_transition_log_pkey PRIMARY KEY (id);

ALTER TABLE ONLY refund
    ADD CONSTRAINT refund_pkey PRIMARY KEY (id);

CREATE INDEX idx_order_id_merchant_id ON order_record USING btree (id, merchant_id);

CREATE INDEX idx_order_merchant_created ON order_record USING btree (merchant_id, created_at);

CREATE INDEX idx_order_merchant_id ON order_record USING btree (merchant_id);

CREATE INDEX idx_order_status_expires_at ON order_record USING btree (order_status, expires_at);

CREATE INDEX idx_outbox_event_status_created_at ON outbox_event USING btree (status, created_at);

CREATE INDEX idx_payment_merchant_captured ON payment USING btree (merchant_id, captured_at);

CREATE INDEX idx_payment_merchant_created ON payment USING btree (merchant_id, created_at);

CREATE INDEX idx_payment_merchant_id ON payment USING btree (merchant_id);

CREATE INDEX idx_payment_order_id ON payment USING btree (order_id);

CREATE INDEX idx_payment_status_created_at ON payment USING btree (status, created_at);

CREATE INDEX idx_payment_transition_log_payment_id ON payment_transition_log USING btree (payment_id);

CREATE INDEX idx_refund_merchant_created ON refund USING btree (merchant_id, created_at);

CREATE INDEX idx_refund_merchant_processed ON refund USING btree (merchant_id, processed_at);

CREATE INDEX idx_refund_payment_id ON refund USING btree (payment_id);

CREATE INDEX idx_refund_status_created_at ON refund USING btree (status, created_at);

ALTER TABLE ONLY payment
    ADD CONSTRAINT fk8tny818kg1ue5ajkn040ed8lm FOREIGN KEY (order_id) REFERENCES order_record(id);

ALTER TABLE ONLY refund
    ADD CONSTRAINT fkeoh1147brjy6m009cswl5lty4 FOREIGN KEY (payment_id) REFERENCES payment(id);

ALTER TABLE ONLY payment_transition_log
    ADD CONSTRAINT fki15m2a3cnw0l7p1jfc1pvx15a FOREIGN KEY (payment_id) REFERENCES payment(id);
