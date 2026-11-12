# Litnet Metadata Provider — Design

**Date:** 2026-11-12
**Status:** Implemented

## Intent

Add `litnet.com` as an opt-in Russian metadata provider following the LitRes
and Fantasy Worlds architecture.

## Source Contract

- Search: `GET https://litnet.com/ru/search?q={query}` (20 books per page).
- Details: `GET https://litnet.com/ru/book/x-b{id}` (the slug is not validated;
  `x` avoids the redirect a bare `-b{id}` URL produces).
- Both pages are Angular SSR; metadata lives in
  `<script id="ng-state" type="application/json">`.
- The detail page also has a JSON-LD `Book` block with `aggregateRating`,
  `description`, `datePublished`, `genre` and `image`.
- Cloudflare fronts the site. Requests use browser headers, a per-parser cookie
  jar, a one-second politeness interval, and retries on 403/429/5xx/challenge.

## Decisions

- **Search reads `items-books-*` from `ng-state`.** The site has no public JSON API.
- **Details read the book node from `ng-state` and use JSON-LD as fallback**
  (and for the average rating, which search results do not expose).
- **No ISBN.** Litnet has no ISBN data, so an ISBN-only query returns an empty
  result and logs that the source cannot search by ISBN. If a title/author is
  also present, the query proceeds normally and the ISBN field stays empty.
- **Search rating semantics.** The search-state `rating` is a count, not an
  average; it is mapped to `litnetReviewCount`. The average rating is available
  only from the detail JSON-LD and fills `litnetRating` there.
- **Authors.** Search results usually expose the uploader/publisher as
  `author`; the detail page has the real author (and optional co-author), so the
  detail fetch is authoritative.
- **Cover.** `image_original` is used when present; otherwise the search
  `image` URL is rewritten from `/covers/220/` to `/covers/0/`.
- **Genres/tags.** `genres[].name` and `tags[].name` (whitespace trimmed).
- **Series/cycle.** `cycle` is often `null`; when present, its `name` is stored
  as `seriesName` and `priority_cycle` as `seriesNumber`.
- **Persistence.** `litnetId`, `litnetRating`, `litnetReviewCount` are stored in
  `book_metadata` (migration `V153`), sidecar, refresh options, browse filters,
  sorting, rules and the v2 API. They are not embedded into file metadata.
- **Opt-in.** Disabled by default.

## Non-goals and known limitations

- No Cloudflare bypass/proxy; if Cloudflare blocks the deployment IP, the
  provider returns empty results rather than solving a challenge.
- No ISBN support (source limitation).
- Search results do not include a series name or average rating; those require
  the detail page.
- The detail fetch adds at least one second of politeness delay per request.
