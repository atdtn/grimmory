# Labirint Metadata Provider — Design

**Date:** 2026-11-13
**Status:** Implemented

## Intent

Add `labirint.ru` as an opt-in source of precise bibliographic metadata: ISBN,
publisher, series and page count, alongside title, authors, annotation, year,
cover and rating.

## Source Contract

- Search: `GET https://www.labirint.ru/search/{urlencoded_query}/` — the
  trailing slash is required. Pagination uses `?page=N`.
- Details: `GET https://www.labirint.ru/books/{id}/`.
- Search is HTML (`div.product-card[data-product-id]`), details are Nuxt SSR
  HTML with schema.org microdata, `meta` tags, `id="annotation"` and labelled
  feature blocks.
- Labirint is not behind Cloudflare. It sets `PHPSESSID`, `UserSes` and related
  cookies. Requests use browser headers, a per-parser cookie jar, a one-second
  politeness interval and retries on 403/429/5xx.

## Decisions

- **Search cards** are filtered to books by `data-dir="books"`,
  `data-sgenre-name="книга"` or a `/books/` link. Games, calendars and
  stationery are discarded.
- **Top three search results are hydrated from detail pages.** Cards carry
  title, authors, cover, publisher and series, but ISBN, page count, exact year
  and rating exist only on the detail page. Hydration is bounded because detail
  pages are large and requests are paced. Detail values are merged over the
  card so a gap in detail parsing never removes card data.
- **ISBN is a hard filter.** When an ISBN is supplied, only results whose
  ISBN-10/ISBN-13 matches are returned; fuzzy non-matching cards are never
  applied. ISBN-10 and ISBN-13 are normalized before comparison.
- **Detail parsing avoids hashed Nuxt classes.** It uses:
  - `h1[itemprop=name]` plus the author suffix cleanup;
  - schema.org `meta[itemprop=isbn|ratingValue|reviewCount]`;
  - `#annotation` (largest text block);
  - text labels ("Автор", "Издательство", "Серия", "Страниц", "ISBN") and
    stable links (`/authors/`, `/pubhouse/`, `/series/`, `/genres/`);
  - `og:image` for the full cover and `link[rel=canonical]` for the URL.
- **ISBN search** uses the required trailing-slash search URL and tries raw and
  digits-only ISBN forms. When a title/author is also present, the ISBN is a
  normalized filter after hydration.
- **Author search** uses the shared patronymic fallbacks
  (`Галина Васильевна Герасимова` → `Галина Герасимова`).
- **Persistence:** `labirintId`, `labirintRating`, `labirintReviewCount` are
  stored in `book_metadata` (migration `V154`), sidecar, refresh options,
  browse filters/sorting, rules, duplicate detection, bookdrop and v2 API.
  They are not embedded into file metadata.
- **Language is left unset.** Labirint carries foreign-language editions, so
  hard-coding `ru` would be wrong; the detail page does not expose a reliable
  language field.
- **Lock migration.** `V155` backfills the Russian-provider lock columns for
  books that were fully locked before those providers existed, so upgrading
  does not silently clear the "all fields locked" state.
- **Opt-in:** disabled by default.

## Non-goals and known limitations

- `robots.txt` disallows `/search/`; this provider is opt-in and paced.
- No series number: Labirint exposes the series name only.
- Year is the publication year of the edition, not the year the work was
  written.
- Multiple editions of the same work are possible; ISBN is the strongest
  disambiguator when supplied.
