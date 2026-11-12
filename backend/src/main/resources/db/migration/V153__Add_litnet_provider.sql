-- Add Litnet metadata columns
ALTER TABLE book_metadata
    ADD COLUMN IF NOT EXISTS litnet_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS litnet_rating FLOAT,
    ADD COLUMN IF NOT EXISTS litnet_review_count INT,
    ADD COLUMN IF NOT EXISTS litnet_id_locked BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS litnet_rating_locked BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS litnet_review_count_locked BOOLEAN DEFAULT FALSE;
