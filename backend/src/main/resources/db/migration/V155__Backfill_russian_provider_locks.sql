-- Preserve the "all metadata locked" state for books that were fully locked
-- before the Russian provider columns were introduced. The new provider locks
-- default to FALSE, which would otherwise silently unlock those books.
UPDATE book_metadata
SET litres_id_locked = TRUE, litres_rating_locked = TRUE, litres_review_count_locked = TRUE,
    fantasy_worlds_id_locked = TRUE, fantasy_worlds_rating_locked = TRUE, fantasy_worlds_review_count_locked = TRUE,
    litnet_id_locked = TRUE, litnet_rating_locked = TRUE, litnet_review_count_locked = TRUE,
    labirint_id_locked = TRUE, labirint_rating_locked = TRUE, labirint_review_count_locked = TRUE
WHERE title_locked = TRUE
  AND subtitle_locked = TRUE
  AND publisher_locked = TRUE
  AND published_date_locked = TRUE
  AND description_locked = TRUE
  AND isbn_13_locked = TRUE
  AND isbn_10_locked = TRUE
  AND openlibrary_id_locked = TRUE
  AND asin_locked = TRUE
  AND page_count_locked = TRUE
  AND language_locked = TRUE
  AND amazon_rating_locked = TRUE
  AND amazon_review_count_locked = TRUE
  AND goodreads_rating_locked = TRUE
  AND goodreads_review_count_locked = TRUE
  AND hardcover_rating_locked = TRUE
  AND hardcover_review_count_locked = TRUE
  AND cover_locked = TRUE
  AND audiobook_cover_locked = TRUE
  AND series_name_locked = TRUE
  AND series_number_locked = TRUE
  AND series_total_locked = TRUE
  AND authors_locked = TRUE
  AND categories_locked = TRUE
  AND moods_locked = TRUE
  AND tags_locked = TRUE
  AND goodreads_id_locked = TRUE
  AND hardcover_id_locked = TRUE
  AND hardcover_book_id_locked = TRUE
  AND google_id_locked = TRUE
  AND comicvine_id_locked = TRUE
  AND lubimyczytac_id_locked = TRUE
  AND lubimyczytac_rating_locked = TRUE
  AND ranobedb_id_locked = TRUE
  AND ranobedb_rating_locked = TRUE
  AND audible_id_locked = TRUE
  AND audible_rating_locked = TRUE
  AND audible_review_count_locked = TRUE
  AND applebooks_id_locked = TRUE
  AND applebooks_rating_locked = TRUE
  AND applebooks_review_count_locked = TRUE
  AND douban_id_locked = TRUE
  AND douban_rating_locked = TRUE
  AND douban_review_count_locked = TRUE
  AND reviews_locked = TRUE
  AND narrator_locked = TRUE
  AND abridged_locked = TRUE
  AND age_rating_locked = TRUE
  AND content_rating_locked = TRUE;
