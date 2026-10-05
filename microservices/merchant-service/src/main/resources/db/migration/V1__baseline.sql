-- Baseline of the payflo_merchant schema, taken from the database Hibernate's ddl-auto had built, with the enum check
-- constraints widened to match the enums. Flyway marks this version as already applied on a database that
-- existed before Flyway (baseline-on-migrate), and runs it on an empty one.

CREATE TABLE api_key (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    updated_at timestamp(6) without time zone,
    updated_by character varying(255),
    enabled boolean NOT NULL,
    environment character varying(10) NOT NULL,
    grace_period_expires_at timestamp(6) without time zone,
    key_id character varying(50) NOT NULL,
    key_secret_hash character varying(200) NOT NULL,
    last_used_at timestamp(6) without time zone,
    previous_key_secret_hash character varying(200),
    rotated_at timestamp(6) without time zone,
    merchant_id uuid NOT NULL,
    CONSTRAINT api_key_environment_check CHECK (((environment)::text = ANY ((ARRAY['LIVE'::character varying, 'TEST'::character varying])::text[])))
);

CREATE TABLE app_user (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    updated_at timestamp(6) without time zone,
    updated_by character varying(255),
    email character varying(255) NOT NULL,
    password_hash character varying(255) NOT NULL,
    role character varying(255) NOT NULL,
    merchant_id uuid,
    CONSTRAINT app_user_role_check CHECK (((role)::text = ANY ((ARRAY['OWNER'::character varying, 'ADMIN'::character varying, 'TEAM'::character varying])::text[])))
);

CREATE TABLE customer (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    updated_at timestamp(6) without time zone,
    updated_by character varying(255),
    deleted_at timestamp(6) without time zone,
    email character varying(200),
    name character varying(200),
    phone character varying(20),
    merchant_id uuid NOT NULL
);

CREATE TABLE merchant (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    updated_at timestamp(6) without time zone,
    updated_by character varying(255),
    business_name character varying(100),
    business_type character varying(50),
    contact_number character varying(20),
    email character varying(255) NOT NULL,
    gst_id character varying(20),
    name character varying(200) NOT NULL,
    pan_id character varying(20),
    settlement_bank_account character varying(200),
    settlement_bank_account_holder_name character varying(200),
    settlement_bank_ifsc character varying(20),
    status character varying(200) NOT NULL,
    website_url character varying(200),
    CONSTRAINT merchant_business_type_check CHECK (((business_type)::text = ANY ((ARRAY['LLP'::character varying, 'PROPRIETORSHIP'::character varying, 'PARTNERSHIP'::character varying, 'PRIVATE_LIMITED'::character varying, 'PUBLIC_LIMITED'::character varying, 'TRUST'::character varying])::text[]))),
    CONSTRAINT merchant_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING_KYC'::character varying, 'ACTIVE'::character varying, 'SUSPENDED'::character varying])::text[])))
);

CREATE TABLE merchant_webhook_config (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    updated_at timestamp(6) without time zone,
    updated_by character varying(255),
    enabled boolean NOT NULL,
    event_types character varying(255),
    target_url character varying(500) NOT NULL,
    webhook_secret character varying(255),
    merchant_id uuid NOT NULL
);

ALTER TABLE ONLY api_key
    ADD CONSTRAINT api_key_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app_user
    ADD CONSTRAINT app_user_pkey PRIMARY KEY (id);

ALTER TABLE ONLY customer
    ADD CONSTRAINT customer_pkey PRIMARY KEY (id);

ALTER TABLE ONLY customer
    ADD CONSTRAINT idx_customer_merchant_email UNIQUE (merchant_id, email);

ALTER TABLE ONLY merchant
    ADD CONSTRAINT merchant_pkey PRIMARY KEY (id);

ALTER TABLE ONLY merchant_webhook_config
    ADD CONSTRAINT merchant_webhook_config_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app_user
    ADD CONSTRAINT uk1j9d9a06i600gd43uu3km82jw UNIQUE (email);

ALTER TABLE ONLY merchant
    ADD CONSTRAINT uk22hw5xdmw9ehbp92kr3h9pbh UNIQUE (email);

ALTER TABLE ONLY api_key
    ADD CONSTRAINT uk4rx8a3gpjkagf3diw254x2ery UNIQUE (key_id);

CREATE INDEX idx_api_key_merchant_env ON api_key USING btree (merchant_id, environment, enabled);

CREATE INDEX idx_app_user_merchant_id ON app_user USING btree (merchant_id);

CREATE INDEX idx_customer_email ON customer USING btree (email);

CREATE INDEX idx_customer_merchant_id ON customer USING btree (merchant_id);

CREATE INDEX idx_merchant_status ON merchant USING btree (status);

CREATE INDEX idx_webhook_merchant_id ON merchant_webhook_config USING btree (merchant_id, enabled);

ALTER TABLE ONLY api_key
    ADD CONSTRAINT fke8e15uritb2pto9w7hepoxqor FOREIGN KEY (merchant_id) REFERENCES merchant(id);

ALTER TABLE ONLY customer
    ADD CONSTRAINT fkegoqw2qeun0mllg61gt5jdd6h FOREIGN KEY (merchant_id) REFERENCES merchant(id);

ALTER TABLE ONLY app_user
    ADD CONSTRAINT fkjksmfgh6vcrbc0fow9oecdamf FOREIGN KEY (merchant_id) REFERENCES merchant(id);

ALTER TABLE ONLY merchant_webhook_config
    ADD CONSTRAINT fkt4q07s1ff5ifr43s1u9vmfb5f FOREIGN KEY (merchant_id) REFERENCES merchant(id);
