DELETE FROM compensation_outbox WHERE type = 'RELEASE_COUPON';

ALTER TABLE compensation_outbox DROP CONSTRAINT IF EXISTS chk_compensation_target;
ALTER TABLE compensation_outbox DROP COLUMN IF EXISTS coupon_code;
ALTER TABLE compensation_outbox
    ADD CONSTRAINT chk_compensation_target CHECK (
        type = 'RELEASE_STOCK' AND product_id IS NOT NULL AND delta IS NOT NULL
    );

ALTER TABLE orders
    DROP COLUMN IF EXISTS applied_coupon_code,
    DROP COLUMN IF EXISTS coupon_operation_id,
    DROP COLUMN IF EXISTS discount_amount;
