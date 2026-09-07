-- See Order's Javadoc on paymentMethod/paymentReference — set once, in
-- payOrder(), and read later by a return/refund request to decide how to
-- reverse the order (or, for COD, that there's nothing to reverse).
ALTER TABLE orders ADD COLUMN IF NOT EXISTS payment_method    VARCHAR(20);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS payment_reference VARCHAR(100);
