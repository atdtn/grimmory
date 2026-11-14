# FB2.top Metadata Provider Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `fb2.top` as a fully integrated Grimmory metadata provider with search, detailed metadata, settings UI, persistence and tests.

**Architecture:** A new `Fb2TopParser` scrapes the HTML search page (`/search?text=`) and detail pages (`/{slug}-{id}`) with Jsoup, maps results to `BookMetadata`, and is registered in `BookParserConfig`. Provider identity flows through the `MetadataProvider` enum (`Fb2Top` in JSON), settings DTO (`fb2Top`), `book_metadata` columns and the frontend provider catalog. Since the site has no ISBN, ISBN-only requests short-circuit to an empty list and no ISBN fields are populated.

**Tech Stack:** Java 21+, Spring Boot, Jsoup, `java.net.http.HttpClient`, JUnit 5/Mockito/AssertJ, Flyway; Angular, Transloco, Vitest.

**Spec:** `/home/pavel/projects/grimmory-docs/2026-11-11-russian-metadata-providers-recon.md` (section 3.4) plus the live page structure verified on 2026-11-14.

## Global Constraints

- Provider id is `FB2_TOP` in Java, serialized as `Fb2Top`; settings key is `fb2Top`; label is `FB2.top` in both locales.
- Disabled by default (`isEnabled = false`), no API key or options.
- Search: `GET https://fb2.top/search?text={urlencoded_query}`; the parameter is `text`, never `q`. The site returns at most 40 `.book-card` results and has no pagination.
- Filter search cards by normalized exact title equality (when a title is supplied) and by author match (when an author is supplied). Reject analyses, summaries, commentaries, "Том N", and other suffix/prefix titles.
- ISBN-only requests return `List.of()` without any HTTP call. Never populate `isbn10`/`isbn13` from this provider.
- Detail URL is `https://fb2.top/{slug}-{id}`; store the whole path segment in `fb2TopId`.
- Ignore placeholder covers (`/img/noposter.jpg`, `/img/noposter-lg.jpg`) by leaving `thumbnailUrl` null.
- Browser headers: `User-Agent`, `Accept: text/html...`, `Accept-Language: ru-RU,ru;q=0.9`, `Referer: https://fb2.top/`; keep a cookie jar for the Laravel session cookie.
- 1 s pause between requests; retry 403/429/5xx up to 3 attempts; honour `Retry-After`; 404 is terminal.
- Rating is on a 5-point scale; store as-is. `publishedDate` uses January 1 of `copyrightYear` when present.
- Next Flyway migration is `V156`.
- Log messages in English; do not break existing tests; run backend and frontend suites at the end.

---

### Task 1: Branch, storage and provider identity

**Files:**
- Create: `backend/src/main/resources/db/migration/V156__Add_fb2_top_provider.sql`
- Modify: `backend/src/main/java/org/booklore/model/enums/MetadataProvider.java`
- Modify: `backend/src/main/java/org/booklore/model/dto/settings/MetadataProviderSettings.java`
- Modify: `backend/src/main/java/org/booklore/model/dto/BookMetadata.java`
- Modify: `backend/src/main/java/org/booklore/model/entity/BookMetadataEntity.java`
- Modify: `backend/src/main/java/org/booklore/model/MetadataClearFlags.java`
- Modify: `backend/src/main/java/org/booklore/model/dto/settings/MetadataProviderSpecificFields.java`

**Interfaces:**
- Produces: `MetadataProvider.FB2_TOP` (JSON `Fb2Top`), `MetadataProviderSettings.Fb2Top#isEnabled()/setEnabled`, `BookMetadata#getFb2TopId()/getFb2TopRating()/getFb2TopReviewCount()` plus `*Locked`, entity columns `fb2_top_id VARCHAR(100)`, `fb2_top_rating FLOAT`, `fb2_top_review_count INT` and their `*_locked` booleans.

- [ ] **Step 1:** Create branch `feature/fb2-top-provider` from the current HEAD.
- [ ] **Step 2:** Add `@JsonProperty("Fb2Top") FB2_TOP` after `LABIRINT` in `MetadataProvider`.
- [ ] **Step 3:** Add `@JsonProperty("fb2Top") private Fb2Top fb2Top;` and `public static class Fb2Top { private boolean enabled; }` to `MetadataProviderSettings`.
- [ ] **Step 4:** Add ID/rating/review-count fields and their locks to `BookMetadata`, then the same to `BookMetadataEntity` with `@Column` names `fb2_top_id`, `fb2_top_rating`, `fb2_top_review_count`, `*_locked`; add `fb2TopId` to `trimStringFields()`, to `applyLockToAllFields()` and `areAllFieldsLocked()`.
- [ ] **Step 5:** Add `fb2TopId/fb2TopRating/fb2TopReviewCount` to `MetadataClearFlags`, `MetadataProviderSpecificFields`, `MetadataRefreshOptions.FieldOptions`/`EnabledFields` and defaults in `AppSettingService`.
- [ ] **Step 6:** Create `V156__Add_fb2_top_provider.sql`: add the six columns with `IF NOT EXISTS`, then backfill the three new locks to `TRUE` for rows that already have every prior metadata/provider lock true (same predicate as `V155`, including the Labirint locks).
- [ ] **Step 7:** Run `cd backend && ./gradlew compileJava --no-daemon` and expect success (parser registration follows in Task 2).

### Task 2: Implement `Fb2TopParser` with fixtures and tests

**Files:**
- Create: `backend/src/main/java/org/booklore/service/metadata/parser/Fb2TopParser.java`
- Create: `backend/src/test/java/org/booklore/service/metadata/parser/Fb2TopParserTest.java`
- Create: `backend/src/test/java/org/booklore/service/metadata/parser/Fb2TopParserLiveSmokeTest.java`
- Create fixtures: `backend/src/test/resources/fb2top/search-master.html`, `backend/src/test/resources/fb2top/detail-master.html`, `backend/src/test/resources/fb2top/detail-noposter.html`
- Modify: `backend/src/main/java/org/booklore/config/BookParserConfig.java`

**Interfaces:**
- Produces: `Fb2TopParser implements BookParser, DetailedMetadataProvider`.
- Constructors: `Fb2TopParser(AppSettingService, HttpClient)` and package-private `Fb2TopParser(AppSettingService, HttpClient, long requestIntervalMs)`.
- `fetchMetadata(Book, FetchMetadataRequest)` returns up to 10 filtered, top-3-hydrated `BookMetadata` items.
- `fetchDetailedMetadata(String providerItemId)` accepts `{slug}-{id}` and returns one `BookMetadata` or `null`.

- [ ] **Step 1: Write failing tests** covering: disabled provider returns empty with no HTTP; empty query returns empty; title search parses cards, uses `text` parameter, filters noise titles, maps cover/rating/author/URL; placeholder card cover becomes null; author-only search; ISBN-only returns empty without HTTP; detail parsing maps title/authors/year/genres/series/pages/rating/description/cover/canonical URL and leaves ISBN null; placeholder detail cover becomes null; 429 retry then success; non-retryable 400 returns empty; enum serializes as `"Fb2Top"`.
- [ ] **Step 2: Record fixtures** from the live pages fetched on 2026-11-14, trimmed to the cards/sections the parser reads. Keep one real-cover card, one `noposter` card, one exact-match noise-free title, and at least two noise cards.
- [ ] **Step 3: Run** `cd backend && ./gradlew test --tests org.booklore.service.metadata.parser.Fb2TopParserTest --no-daemon` and expect failure (class missing).
- [ ] **Step 4: Implement** `Fb2TopParser`:
  - constants `SITE_BASE = "https://fb2.top"`, `MAX_RESULTS = 10`, `MAX_HYDRATED_RESULTS = 3`, `MAX_RETRIES = 3`, `DEFAULT_REQUEST_INTERVAL_MS = 1_000L`;
  - `fetchMetadata`: return empty when disabled or when only an ISBN is supplied; build title-only or author-only queries; parse cards; strict normalized title equality + author match; dedupe by `fb2TopId`; hydrate the first three candidates via `fetchDetailedMetadata` and merge;
  - card parsing: `.card-title a[href]` -> id = href without leading `/`, title; `.authors-box` split on commas; `.poster-box img` `data-src` then `src`, placeholder ignored; `.rating-box` double;
  - detail parsing: `h1[itemprop=name]`, all `[itemprop=author]`, `[itemprop=copyrightYear]`, `[itemprop=description] .annotation`, all `[itemprop=genre]`, first `a[href^=/series/]` in the "Входит в серии" row, `[itemprop=numberOfPages]`, `[itemprop=aggregateRating]` (`meta[itemprop=ratingCount]`, any `[itemprop=ratingValue]`), `img.book-info-poster-img`/`og:image`, canonical/`og:url`;
  - HTTP with browser headers, cookie jar, synchronized request interval, retry on 403/429/5xx with `Retry-After`.
- [ ] **Step 5:** Register `fb2TopParser` in `BookParserConfig`.
- [ ] **Step 6: Add the opt-in live smoke test** guarded by `@EnabledIfEnvironmentVariable(named = "FB2_TOP_LIVE_TEST", matches = "true")`: search "Мастер и Маргарита", assert non-empty and exact title/author, fetch details by the first id, assert title/author and no ISBN.
- [ ] **Step 7:** Run the parser tests and expect PASS.
- [ ] **Step 8:** Commit.

### Task 3: Wire backend refresh, update, sidecar and query paths

**Files:**
- Modify: `backend/src/main/java/org/booklore/service/appsettings/AppSettingService.java`
- Modify: `backend/src/main/java/org/booklore/model/dto/settings/MetadataMatchWeights.java`
- Modify: `backend/src/main/java/org/booklore/service/metadata/MetadataRefreshService.java`
- Modify: `backend/src/main/java/org/booklore/service/metadata/BookMetadataUpdater.java`
- Modify: `backend/src/main/java/org/booklore/service/metadata/MetadataMatchService.java`
- Modify: `backend/src/main/java/org/booklore/util/MetadataChangeDetector.java`
- Modify: `backend/src/main/java/org/booklore/model/dto/sidecar/SidecarIdentifiers.java`
- Modify: `backend/src/main/java/org/booklore/model/dto/sidecar/SidecarRatings.java`
- Modify: `backend/src/main/java/org/booklore/service/metadata/sidecar/SidecarMetadataMapper.java`
- Modify: `backend/src/main/java/org/booklore/service/bookdrop/BookdropMetadataService.java`
- Modify: `backend/src/main/java/org/booklore/service/book/DuplicateDetectionService.java`
- Modify: `backend/src/main/java/org/booklore/service/book/BookFileDetachmentService.java`
- Modify: `backend/src/main/java/org/booklore/service/book/BookQueryService.java`
- Modify: `backend/src/main/java/org/booklore/model/dto/RuleField.java`
- Modify: `backend/src/main/java/org/booklore/service/BookRuleEvaluatorService.java`
- Modify: `backend/src/main/java/org/booklore/service/browse/BookFacetRegistry.java`
- Modify: `backend/src/main/java/org/booklore/service/browse/BookSortRegistry.java`
- Modify: `backend/src/main/java/org/booklore/service/browse/BookFilterSpecification.java`

**Interfaces:**
- Consumes: `BookMetadata#getFb2TopId()/getFb2TopRating()/getFb2TopReviewCount()` and entity columns from Task 1.
- Produces: provider-specific fields selectable in refresh options, weights, rules, facets, sorting, duplicate detection, sidecars and detachment.

- [ ] **Step 1:** Add the `Fb2Top` default settings (`enabled = false`), null field providers, enabled fields and zero weights in `AppSettingService`; add weight fields to `MetadataMatchWeights` and its `totalWeight()`.
- [ ] **Step 2:** In `MetadataRefreshService`, include the new field providers in `getAllProvidersUsingIndividualFields`, copy the fields in `applyIndividualProviders` (or the equivalent method), and preserve the locks in the existing-metadata branch.
- [ ] **Step 3:** Add `handleFieldUpdate` calls for the ID/rating/review count and lock pairs in `BookMetadataUpdater`; extend `MetadataMatchService` score calculation.
- [ ] **Step 4:** Add `FieldDescriptor`s in `MetadataChangeDetector` (ID not sidecar-managed; rating/review count sidecar-managed).
- [ ] **Step 5:** Add `fb2TopId` to `SidecarIdentifiers` and `fb2Top` to `SidecarRatings`; map both directions in `SidecarMetadataMapper`.
- [ ] **Step 6:** Add `fb2TopId` to `BookdropMetadataService.hasAnyKnownIdentifier`/`cleanInitialMetadata`, `fb2top:` to `DuplicateDetectionService`, and copy fields in `BookFileDetachmentService`; strip locks and IDs in `BookQueryService`.
- [ ] **Step 7:** Add `FB2_TOP_RATING`/`FB2_TOP_REVIEW_COUNT` to `RuleField`, the field-value switch, numeric-fields set and string/numeric presence switches; add `fb2_top_rating` to `BookFacetRegistry`, the two sort keys to `BookSortRegistry` and `withFb2TopRatings` to `BookFilterSpecification`.
- [ ] **Step 8:** Run `cd backend && ./gradlew test --no-daemon` and expect PASS; commit.

### Task 4: Wire the app/API-v2 surface

**Files:**
- Modify: `backend/src/main/java/org/booklore/app/dto/AppBookSummary.java`
- Modify: `backend/src/main/java/org/booklore/app/dto/AppFilterOptions.java`
- Modify: `backend/src/main/java/org/booklore/app/dto/BookListRequest.java`
- Modify: `backend/src/main/java/org/booklore/app/mapper/AppBookMapper.java`
- Modify: `backend/src/main/java/org/booklore/app/service/AppBookService.java`
- Modify: `backend/src/main/java/org/booklore/app/specification/AppBookSpecification.java`

**Interfaces:**
- Produces: `fb2TopRating/fb2TopReviewCount` on summaries; `fb2TopRatings` in filter options; `fb2TopRating` request field and filter; `withFb2TopRatings`; sort mapping for `fb2toprating`/`fb2topreviewcount`.

- [ ] **Step 1:** Add summary fields and mapper mappings, filter option list and empty list, request filter field, `AppBookService` bucket/query/filter/sort handling, and `AppBookSpecification.withFb2TopRatings`.
- [ ] **Step 2:** Run `cd backend && ./gradlew test --no-daemon` and expect PASS; commit.

### Task 5: Frontend provider catalogue and models

**Files:**
- Modify: `frontend/src/app/shared/metadata/metadata-providers.ts`
- Modify: `frontend/src/app/shared/model/app-settings.model.ts`
- Modify: `frontend/src/app/features/book/data/book-response.models.ts`
- Modify: `frontend/src/app/features/book/model/book.model.ts`
- Modify: `frontend/src/app/features/book/data/book-query-params.ts`
- Modify: `frontend/src/app/features/book/browse/book-browse-fields.ts`
- Modify: `frontend/src/app/features/book/browse/book-browse-detail-line.service.ts`
- Modify: `frontend/src/app/features/metadata/sources/sidecar.models.ts`
- Modify: `frontend/src/app/features/stats/component/user-stats/charts/rating-taste-chart/rating-taste-chart.component.ts`
- Modify: `frontend/src/app/features/metadata/component/book-metadata-center/metadata-searcher/metadata-searcher.component.scss`
- Modify: `frontend/src/i18n/en.json`, `frontend/src/i18n/ru.json`

**Interfaces:**
- Produces: provider descriptor `{id: 'Fb2Top', settingsKey: 'fb2Top', labelKey: 'metadata.providers.fb2Top', book: {ids: ['fb2TopId'], rating: 'fb2TopRating', reviewCount: 'fb2TopReviewCount'}}`, book/metadata fields, filter key `fb2_top_rating`, sort keys `fb2TopRating`/`fb2TopReviewCount`, sidecar rating `fb2Top`, and `FB2.top` labels.

- [ ] **Step 1:** Add the provider descriptor and all model fields/locks/flags; add the settings interface/property, response models, book model, query facet/sort keys, browse fields/detail line, sidecar rating, rating chart average and provider colour.
- [ ] **Step 2:** Add `metadata.providers.fb2Top` (`FB2.top`) and `book.fields.fb2TopRating`/`fb2TopReviewCount` to both locales.
- [ ] **Step 3:** Update specs that enumerate provider fields/weights/provider counts and the searcher settings fixture.
- [ ] **Step 4:** Run `cd frontend && pnpm run typecheck && pnpm run test` and expect PASS; commit.

### Task 6: Final verification

- [ ] **Step 1:** Run `cd backend && ./gradlew test --no-daemon`.
- [ ] **Step 2:** Run `cd frontend && pnpm run typecheck && pnpm run test`.
- [ ] **Step 3:** Run `git status --short` and confirm only the intended files changed; commit any remaining changes.
