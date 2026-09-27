ALTER TABLE coupon_redemptions
    ADD COLUMN IF NOT EXISTS subtotal_amount NUMERIC(12,2);

-- V2 created the idempotency table before the subtotal became part of the
-- operation identity. Existing rows are tombstones only when created by the
-- compensation path, so zero is a safe backfill; normal redemption rows are
-- written with their real subtotal after this migration.
UPDATE coupon_redemptions
SET subtotal_amount = COALESCE(subtotal_amount, 0)
WHERE subtotal_amount IS NULL;

ALTER TABLE coupon_redemptions
    ALTER COLUMN subtotal_amount SET NOT NULL;
