# Fantasy Worlds Metadata Provider — Design

**Date:** 2026-11-11
**Status:** Implemented

## Intent

Add `fantasy-worlds.org` as an opt-in metadata provider for Russian-language
books, following the LitRes provider architecture.

## Source Contract

- Search: `GET https://fantasy-worlds.org/search.json?q={query}`
  returns `{books, authors, keywords}`. The server caps results at 50.
- Details: `GET https://fantasy-worlds.org/lib/id{id}/` returns HTML with
  schema.org Book microdata.
- `robots.txt` requests `Crawl-delay: 10`; the parser throttles every request
  to the host to one per ten seconds.
- The site does not search by ISBN (`search.json?q=ISBN` returns no books), so
  ISBN is used for filtering when title/author search returns candidates.

## Decisions

- **Search uses JSON only, details are lazy.** The search JSON already has
  title, authors, annotation, year, ISBN, series, rating and cover, so a search
  makes one request. `DetailedMetadataProvider` parses the HTML page on demand;
  this avoids ten-second delays per search result.
- **Rating scale.** The site uses 0–10 (`ratio: "9.6/58"`). Grimmory provider
  ratings are displayed on a 5-point scale, so the value is divided by two
  (`4.8`, review count `58`), matching the LubimyCzytac provider.
- **Series number.** `serNumb` is the actual position in the series (verified
  against the detail page); `series_order` is only a fallback.
- **ISBN.** The JSON may contain several comma-separated ISBNs with leading
  spaces. The first valid 10/13-digit ISBN is normalized.
- **Genres.** The site exposes only a numeric `genre` id and no public name
  lookup, so it is stored as a stable category `genre:<id>`. Human-readable
  tags are parsed from the detail page (`a[href^=/lib/tag]`).
- **Covers.** Full-size cover URL:
  `https://fantasy-worlds.org/img/full/{floor(id/100)}/{id}.{book_ext}`.
- **Persistence.** `fantasyWorldsId`, `fantasyWorldsRating`,
  `fantasyWorldsReviewCount` are stored in `book_metadata` (migration `V152`),
  sidecar files, refresh options, browse filters/sorting and the v2 API.
  They are not embedded into EPUB/PDF/CBX files.
- **Opt-in.** The provider is disabled by default.

## Non-goals and known limitations

- No Cloudflare/proxy handling (not needed for this site).
- No automated crawl of the whole catalogue.
- No human-readable genre mapping until the site exposes a genre lookup.
- The crawl-delay is enforced response-to-next-request and serializes all
  requests from one parser instance. Library-wide metadata refreshes therefore
  spend at least ten seconds per book in the existing per-book transaction;
  this is inherited from the current refresh architecture and should be
  addressed separately if large refreshes become common.
- Detail pages do not expose a genre id, so `categories` is populated from the
  search JSON and `tags` from the detail page; a detail-only fetch has no
  categories.
