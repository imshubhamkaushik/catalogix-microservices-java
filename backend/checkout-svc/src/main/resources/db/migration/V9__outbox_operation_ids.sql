-- Idempotency keys for queued stock releases (see inventory-svc V2).
--   operation_id : unique id of this release, so a replay after a crash is a no-op
--   undo_of      : id of the reservation it reverses, so releasing a reservation that
--                  never actually reached inventory-svc adds nothing back
-- NULL on rows queued before this migration (they keep the old, non-idempotent behaviour).

ALTER TABLE compensation_outbox
    ADD COLUMN IF NOT EXISTS operation_id VARCHAR(120),
    ADD COLUMN IF NOT EXISTS undo_of      VARCHAR(120);
