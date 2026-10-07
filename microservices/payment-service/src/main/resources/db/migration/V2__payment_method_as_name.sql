-- payment.method was stored as the ordinal of PaymentMethod (0 CARD, 1 NETBANKING, 2 UPI, 3 WALLET), which made the
-- order of the enum part of the database format: adding or moving a constant would silently change what every row means.
-- It is now the constant's name.
ALTER TABLE payment DROP CONSTRAINT payment_method_check;

ALTER TABLE payment ALTER COLUMN method TYPE character varying(20) USING (
    CASE method
        WHEN 0 THEN 'CARD'
        WHEN 1 THEN 'NETBANKING'
        WHEN 2 THEN 'UPI'
        WHEN 3 THEN 'WALLET'
    END);

ALTER TABLE payment ADD CONSTRAINT payment_method_check CHECK (method IN ('CARD', 'NETBANKING', 'UPI', 'WALLET'));
