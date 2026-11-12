-- Add Labirint metadata columns
ALTER TABLE book_metadata
    ADD COLUMN IF NOT EXISTS labirint_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS labirint_rating FLOAT,
    ADD COLUMN IF NOT EXISTS labirint_review_count INT,
    ADD COLUMN IF NOT EXISTS labirint_id_locked BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS labirint_rating_locked BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS labirint_review_count_locked BOOLEAN DEFAULT FALSE;
