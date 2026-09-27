ALTER TABLE seller_payouts ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(80);
UPDATE seller_payouts SET idempotency_key = 'legacy-payout-' || id WHERE idempotency_key IS NULL;
ALTER TABLE seller_payouts ALTER COLUMN idempotency_key SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_payout_idempotency ON seller_payouts(idempotency_key);
