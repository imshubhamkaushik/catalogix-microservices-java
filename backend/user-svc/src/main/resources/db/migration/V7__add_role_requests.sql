-- Role approval workflow.
--
-- Everyone who registers is a customer (USER). A customer can ASK to become a
-- seller; an admin then approves (assigns the role) or rejects. Admins are the
-- only ones who can assign SELLER or ADMIN. These two columns hold a pending
-- request; both are NULL when there is none. Approving or rejecting clears them.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS requested_role    VARCHAR(20),
    ADD COLUMN IF NOT EXISTS role_requested_at TIMESTAMPTZ;
