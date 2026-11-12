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
  pages are large and requests are paced.
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
- **Opt-in:** disabled by default.

## Non-goals and known limitations

- `robots.txt` disallows `/search/`; this provider is opt-in and paced.
- No series number: Labirint exposes the series name only.
- Year is the publication year of the edition, not the year the work was
  written.
- Multiple editions of the same work are possible; ISBN is the strongest
  disambiguator when supplied.
