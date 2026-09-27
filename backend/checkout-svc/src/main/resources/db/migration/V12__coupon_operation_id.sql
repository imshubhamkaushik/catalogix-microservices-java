ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS coupon_operation_id VARCHAR(160);
