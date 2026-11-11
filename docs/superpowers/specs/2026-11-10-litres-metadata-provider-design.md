# LitRes Metadata Provider — Design

**Date:** 2026-11-10
**Status:** Approved

## Intent

Add a first-class metadata provider for Russian-language books, starting with
LitRes (`litres.ru`), and leave a clear extension point for Litnet later.
Users must be able to enable the provider in Grimmory settings, search by
title/author/ISBN, and import full metadata including a high-resolution cover.

## Why

Grimmory's existing providers are Western-centric. LitRes is the largest
Russian ebook catalogue and exposes the metadata Russian readers expect:
Russian titles, translators, Russian genres/tags, series order and ISBNs.

## Goals

- `LitresParser` (the LitRes provider) implementing `BookParser` and
  `DetailedMetadataProvider`.
- Search by title, author or ISBN through LitRes' internal JSON API.
- Fetch title, subtitle, authors + translators, description, high-resolution
  cover, genres, tags, series + number, publication date, ISBN, page count,
  language, publisher, rating and review count.
- Enable/disable from the existing metadata-provider settings UI.
- Persist `litresId`, `litresRating`, `litresReviewCount` like every other
  provider.
- Unit tests with recorded API fixtures plus a live smoke test of three queries.
- Architecture that lets Litnet be added as another provider without touching
  LitRes code.

## Non-goals

- No Litnet implementation in this change.
- No HTML scraping; the JSON API is used. HTML remains a fallback only if the
  API changes, and that is a future task.
- No translator-specific persistence: Grimmory has no translator field, so
  translators are appended to `authors`, following the existing Douban
  provider precedent.
- No UDC/BBK: LitRes' API does not expose them for ebooks. Fields are simply
  left empty.
- No proxy/anti-captcha infrastructure.

## Constraints

- Existing code must keep compiling and all current tests must pass.
- Provider settings must be opt-in (disabled by default).
- All logs/messages in English, following project style.
- Requests must carry browser-like headers, timeouts, and retries on
  403/429/5xx with backoff, honouring `Retry-After`.
- One Flyway migration, next free version (`V151`).

## Approaches Considered

1. **Internal JSON API + full provider integration (chosen).** Stable structured
   data, no auth, supports details, genres and high-res covers. Requires the
   normal provider plumbing (enum, settings, DB, UI).
2. **JSON API but no new persisted fields.** Cheaper, but the provider would be
   second-class and could not be refreshed/detailed by ID.
3. **HTML scraping.** Works without the internal API but is brittle, slow and
   harder to extend to Litnet.

## API Contract Used

- Search: `GET https://api.litres.ru/foundation/api/search`
  - `q` — query string
  - `types=text_book`
  - `limit` — max results
- Details: `GET https://api.litres.ru/foundation/api/arts/{id}`
- Cover: `https://cdn2.litres.ru/pub/c/cover/{id}.jpg` (original), with
  `cover_max1500` available if the original is ever too large.

Both endpoints return `{"status":200,"payload":{...}}`; errors use
`status`/`error`. No authentication is required for public catalogue data.

## Field Mapping

| Grimmory | LitRes source |
|---|---|
| title | `title` |
| subtitle | `subtitle` |
| authors | `persons[]` where `role == "author"`, then translators appended |
| description | `html_annotation` stripped to text |
| thumbnailUrl | `https://cdn2.litres.ru/pub/c/cover/{id}.jpg` |
| categories | `genres[].name` |
| tags | `tags[].name` |
| seriesName / seriesNumber | `series[0].name` / `series[0].art_order` |
| publishedDate | `publication_date`, fallback `date_written_at` |
| isbn13 / isbn10 | `isbn`, split by digit length |
| pageCount | `additional_info.current_pages_or_seconds` |
| language | `language_code` |
| publisher | `publisher.name`, fallback `copyrighter.name` |
| litresId | `id` |
| litresRating | `rating.rated_avg` |
| litresReviewCount | `rating.rated_total_count` |
| externalUrl | `https://www.litres.ru` + `url` |

## Parts Decided

- Class name `LitresParser`, because every provider in this codebase uses the
  `*Parser` suffix; it *is* the `LitresProvider` conceptually.
- Search first by exact ISBN (LitRes matches hyphenated ISBNs), then by
  title + author, then by author alone. Each result is hydrated with the
  details endpoint; limit 10 hydrated results.
- Translator names are appended to `authors` (Douban precedent). Duplicates are
  removed while preserving order.
- New persisted fields use the names `litresId`, `litresRating`,
  `litresReviewCount` in Java/TypeScript and `litres_id`, `litres_rating`,
  `litres_review_count` in SQL.
- Settings: simple toggle, key `litres`, no options.
- Litnet extension: add `MetadataProvider.Litnet`, a settings block, migration,
  frontend descriptor, and a `LitnetParser`. The HTTP retry/request code lives
  in `LitresParser` but is written as small private methods so it can be
  extracted into a shared helper when the second Russian provider arrives.
  No premature shared abstraction now.
- Coverage: unit tests use recorded JSON fixtures for search + details and
  verify disabled/empty-query/no-results/retry behavior. A manual live smoke
  test runs the three required queries against the real API.

## Left to the Builder

- Exact test fixture contents (recorded from the API on 2026-11-10).
- Whether to cap description length; no cap is required by the spec.
- Formatting of stripped HTML annotation (Paragraphs separated by blank lines).
