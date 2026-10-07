-- Baseline of the payflo_vault schema, taken from the database Hibernate's ddl-auto had built, with the enum check
-- constraints widened to match the enums. Flyway marks this version as already applied on a database that
-- existed before Flyway (baseline-on-migrate), and runs it on an empty one.

CREATE TABLE card_token (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    updated_at timestamp(6) without time zone,
    updated_by character varying(255),
    customer uuid,
    merchant uuid NOT NULL,
    revoked_at timestamp(6) without time zone,
    token character varying(50) NOT NULL,
    vault_card_id uuid NOT NULL
);

CREATE TABLE vault_card (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    updated_at timestamp(6) without time zone,
    updated_by character varying(255),
    bin character varying(6) NOT NULL,
    brand character varying(255) NOT NULL,
    card_holder_name character varying(255) NOT NULL,
    deleted_at timestamp(6) without time zone,
    encrypted_dek bytea NOT NULL,
    encrypted_pan bytea NOT NULL,
    expiry_month character varying(255) NOT NULL,
    expiry_year character varying(255) NOT NULL,
    last_four character varying(4) NOT NULL,
    CONSTRAINT vault_card_brand_check CHECK (((brand)::text = ANY ((ARRAY['VISA'::character varying, 'MASTERCARD'::character varying, 'RUPAY'::character varying, 'AMEX'::character varying])::text[])))
);

ALTER TABLE ONLY card_token
    ADD CONSTRAINT card_token_pkey PRIMARY KEY (id);

ALTER TABLE ONLY card_token
    ADD CONSTRAINT uk53dm4jjlxyrmypgcuohutmw1j UNIQUE (token);

ALTER TABLE ONLY vault_card
    ADD CONSTRAINT vault_card_pkey PRIMARY KEY (id);

ALTER TABLE ONLY card_token
    ADD CONSTRAINT fkfcuhwnqaybadjnid0pigv3n0g FOREIGN KEY (vault_card_id) REFERENCES vault_card(id);
