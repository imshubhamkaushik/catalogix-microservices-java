-- Rows created before V9 did not have operation ids, which would make a
-- retrying outbox worker fall back to the legacy non-idempotent adjustment.
-- Give every existing row a stable key derived from its immutable outbox id.
UPDATE compensation_outbox
SET operation_id = CASE type
        WHEN 'RELEASE_STOCK' THEN 'legacy-stock-release:' || id
        WHEN 'RELEASE_COUPON' THEN 'legacy-coupon-release:' || id
        ELSE operation_id
    END
WHERE operation_id IS NULL;
