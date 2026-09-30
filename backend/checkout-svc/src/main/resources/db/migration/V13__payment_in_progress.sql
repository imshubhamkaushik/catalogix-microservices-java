-- PAYMENT_PROCESSING order status + the timestamp of the latest payment attempt.
-- orders.status is VARCHAR(20) with no CHECK constraint, and 'PAYMENT_PROCESSING'
-- is 18 characters, so no change to that column is needed.
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS payment_started_at TIMESTAMPTZ;

-- Tiny partial index: only rows currently mid-payment, which is what the
-- stale-claim sweep scans every minute.
CREATE INDEX IF NOT EXISTS idx_orders_payment_processing
    ON orders (payment_started_at)
    WHERE status = 'PAYMENT_PROCESSING';
