-- See Order's Javadoc on customerEmail — snapshotted at payment time,
-- purely so the invoice endpoint (GET /orders/{id}/invoice) has a customer
-- identity to print without a live cross-service call to user-svc.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS customer_email VARCHAR(255);
