-- Add Fantasy Worlds metadata columns
ALTER TABLE book_metadata
    ADD COLUMN IF NOT EXISTS fantasy_worlds_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS fantasy_worlds_rating FLOAT,
    ADD COLUMN IF NOT EXISTS fantasy_worlds_review_count INT,
    ADD COLUMN IF NOT EXISTS fantasy_worlds_id_locked BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS fantasy_worlds_rating_locked BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS fantasy_worlds_review_count_locked BOOLEAN DEFAULT FALSE;
