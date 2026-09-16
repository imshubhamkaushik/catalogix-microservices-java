-- Adds an optional image URL to products. Deliberately just a URL string,
-- not a file-upload/storage pipeline (S3 bucket, multipart upload endpoint,
-- CDN, etc.) — that's a real infra decision (which storage backend for
-- local vs. prod, image resizing, CDN) that deserves its own pass rather
-- than being bolted on here. A URL field ships the actual user-visible
-- value (a real photo on the product card instead of a generic icon) today;
-- swapping it for a full upload pipeline later doesn't require touching
-- this column at all, an upload endpoint would just end up writing a URL
-- here same as this does.
ALTER TABLE products
    ADD COLUMN IF NOT EXISTS image_url VARCHAR(500);
