# LitRes Metadata Provider Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Add LitRes as a fully integrated Grimmory metadata provider with search, detailed metadata, settings UI, persistence and tests.

**Architecture:** A new `LitresParser` calls LitRes' public JSON catalogue API, maps results to `BookMetadata`, and is registered in `BookParserConfig`. Provider identity flows through the `MetadataProvider` enum, settings DTO, `book_metadata` columns and the frontend provider catalog.

**Tech Stack:** Java 21+, Spring Boot, Jackson, `java.net.http.HttpClient`, JUnit 5/Mockito/AssertJ, Flyway; Angular, Transloco, Vitest.

**Spec:** `docs/superpowers/specs/2026-11-10-litres-metadata-provider-design.md`

## Global Constraints

- LitRes must be disabled by default.
- Requests must send browser `User-Agent`, `Accept: application/json`, `Accept-Language: ru-RU,ru;q=0.9`, `Referer: https://www.litres.ru/`.
- Request timeout 15s; retry 403/429/5xx up to 3 attempts; honour `Retry-After`; do not retry other 4xx.
- Search limit: 10 hydrated results.
- Translator names go into `authors` after real authors, de-duplicated.
- Log messages in English.
- Do not break existing tests; run backend and frontend suites at the end.
- Next Flyway migration is `V151`.

---

### Task 1: Register provider identity and storage

**Files:**
- Modify: `backend/src/main/java/org/booklore/model/enums/MetadataProvider.java`
- Modify: `backend/src/main/java/org/booklore/model/dto/settings/MetadataProviderSettings.java`
- Modify: `backend/src/main/java/org/booklore/model/dto/BookMetadata.java`
- Modify: `backend/src/main/java/org/booklore/model/entity/BookMetadataEntity.java`
- Modify: `backend/src/main/java/org/booklore/config/BookParserConfig.java` (after parser exists; add in Task 2)
- Create: `backend/src/main/resources/db/migration/V151__Add_litres_provider.sql`
- Create: `backend/src/main/java/org/booklore/model/dto/settings/MetadataProviderSpecificFields.java` additions

**Interfaces:**
- Produces: `MetadataProvider.Litres`, `MetadataProviderSettings.Litres#isEnabled()/setEnabled`, `BookMetadata#getLitresId()/getLitresRating()/getLitresReviewCount()`, entity columns `litres_id`, `litres_rating`, `litres_review_count` and `*_locked`.

- [x] **Step 1:** Add `Litres` to the enum after `Lubimyczytac`.
- [x] **Step 2:** Add `private Litres litres;` and `public static class Litres { private boolean enabled; }` to `MetadataProviderSettings`.
- [x] **Step 3:** Add `litresId`, `litresRating`, `litresReviewCount` plus their `*Locked` booleans to `BookMetadata`.
- [x] **Step 4:** Add the same fields to `BookMetadataEntity` with `@Column` names `litres_id` (length 100), `litres_rating`, `litres_review_count`; add `litresId` to `trimStringFields()`.
- [x] **Step 5:** Add `litresId/litresRating/litresReviewCount` to `MetadataProviderSpecificFields`.
- [x] **Step 6:** Create migration `V151__Add_litres_provider.sql` adding columns `litres_id VARCHAR(100)`, `litres_rating FLOAT`, `litres_review_count INT`, and the three `*_locked BOOLEAN DEFAULT FALSE` columns with `IF NOT EXISTS`.
- [x] **Step 7:** Run `cd backend && ./gradlew compileJava --no-daemon` and expect success (parser config registration follows in Task 2).

### Task 2: Implement `LitresParser` with tests

**Files:**
- Create: `backend/src/main/java/org/booklore/service/metadata/parser/LitresParser.java`
- Create: `backend/src/test/java/org/booklore/service/metadata/parser/LitresParserTest.java`
- Create fixtures: `backend/src/test/resources/litres/search-dune.json`, `backend/src/test/resources/litres/detail-dune.json`, `backend/src/test/resources/litres/search-author.json`
- Modify: `backend/src/main/java/org/booklore/config/BookParserConfig.java`

**Interfaces:**
- Produces: `LitresParser implements BookParser, DetailedMetadataProvider`
- Constructor: `LitresParser(ObjectMapper objectMapper, AppSettingService appSettingService, HttpClient httpClient)`
- `fetchMetadata(Book, FetchMetadataRequest)` returns hydrated `BookMetadata` list.
- `fetchDetailedMetadata(String providerItemId)` returns one `BookMetadata` or `null`.

- [x] **Step 1: Write failing tests** covering: disabled provider returns empty; empty query returns empty; title search maps title/authors/translators/description/cover/genres/tags/series/ISBN/date/pages/rating; author-only search works; detail fetch maps by numeric ID; 429 retries then succeeds; non-retryable 400 returns empty.
- [x] **Step 2: Record fixtures** from the live API (already verified on 2026-11-10) and trim them to the fields the parser reads.
- [x] **Step 3: Run tests** `cd backend && ./gradlew test --tests org.booklore.service.metadata.parser.LitresParserTest --no-daemon` and expect failure (class missing).
- [x] **Step 4: Implement** `LitresParser`:
  - constants `API_BASE = "https://api.litres.ru/foundation/api"`, `SITE_BASE = "https://www.litres.ru"`, `CDN_BASE = "https://cdn2.litres.ru"`, `MAX_RESULTS = 10`;
  - `fetchMetadata`: ISBN first, then title+author, then author; search each result ID; limit 10;
  - `fetchDetailedMetadata`: GET `/arts/{id}`;
  - `parseDetail(JsonNode)`: map table from the spec; strip HTML via Jsoup; split ISBN; join authors + translators; build cover URL; `externalUrl`.
  - `get(String path/query)`: request with headers and timeout; retry logic with `Thread.sleep` backoff and `Retry-After`; returns `null` on terminal failure.
- [x] **Step 5:** Register in `BookParserConfig` constructor parameter and `Map.entry(MetadataProvider.Litres, litresParser)`.
- [x] **Step 6:** Run the parser test and expect PASS.
- [x] **Step 7:** Commit.

### Task 3: Wire backend refresh/update/settings paths

**Files:**
- Modify: `backend/src/main/java/org/booklore/service/appsettings/AppSettingService.java`
- Modify: `backend/src/main/java/org/booklore/model/dto/request/MetadataRefreshOptions.java`
- Modify: `backend/src/main/java/org/booklore/service/metadata/MetadataRefreshService.java`
- Modify: `backend/src/main/java/org/booklore/service/metadata/BookMetadataUpdater.java`
- Modify: `backend/src/main/java/org/booklore/util/MetadataChangeDetector.java`
- Modify: `backend/src/main/java/org/booklore/model/MetadataClearFlags.java`
- Modify: `backend/src/main/java/org/booklore/model/dto/settings/MetadataMatchWeights.java`
- Modify: `backend/src/main/java/org/booklore/service/metadata/MetadataMatchService.java`
- Modify: `backend/src/main/java/org/booklore/service/browse/BookFacetRegistry.java`
- Modify: `backend/src/main/java/org/booklore/service/browse/BookFilterSpecification.java`
- Modify: `backend/src/main/java/org/booklore/service/browse/BookSortRegistry.java`
- Modify: `backend/src/main/java/org/booklore/app/specification/AppBookSpecification.java`
- Modify: `backend/src/main/java/org/booklore/model/dto/RuleField.java`
- Modify: `backend/src/main/java/org/booklore/service/BookRuleEvaluatorService.java`

**Interfaces:**
- Consumes: Task 1 getters/setters and enum value.
- Produces: default settings include a disabled `Litres`; refresh options have `litresId/litresRating/litresReviewCount`; merge logic copies LitRes values when enabled.

- [x] **Step 1:** In `AppSettingService`, add default `MetadataProviderSettings.Litres` disabled; add `litresId/litresRating/litresReviewCount` to default refresh `FieldOptions`, `EnabledFields`, match weights, provider-specific fields.
- [x] **Step 2:** In `MetadataRefreshOptions`, add the three `FieldProvider` fields and three `EnabledFields` booleans (default true, `@JsonSetter(nulls = Nulls.SKIP)`).
- [x] **Step 3:** In `MetadataRefreshService`, add the three fields to `getAllProvidersUsingIndividualFields`, the merge block, and the lock-copy block.
- [x] **Step 4:** In `BookMetadataUpdater`, add three `handleFieldUpdate` calls and three lock mappings.
- [x] **Step 5:** In `MetadataChangeDetector`, add three `FieldDescriptor` entries.
- [x] **Step 6:** Add the three flags to `MetadataClearFlags`.
- [x] **Step 7:** Add `litresRating`/`litresReviewCount` to `MetadataMatchWeights`, `totalWeight()`, and `MetadataMatchService` scoring.
- [x] **Step 8:** Add LitRes rating to browse facet/filter/sort and rule field support following the existing `lubimyczytac` lines.
- [x] **Step 9:** Run `cd backend && ./gradlew test --tests org.booklore.service.metadata.* --no-daemon` and expect PASS.
- [x] **Step 10:** Commit.

### Task 4: Frontend provider catalog, models and settings UI

**Files:**
- Modify: `frontend/src/app/shared/metadata/metadata-providers.ts`
- Modify: `frontend/src/app/shared/model/app-settings.model.ts`
- Modify: `frontend/src/app/features/book/model/book.model.ts`
- Modify: `frontend/src/app/features/book/data/book-response.models.ts`
- Modify: `frontend/src/i18n/en.json`
- Modify: `frontend/src/i18n/ru.json`
- Modify tests: `frontend/src/app/shared/metadata/metadata-provider-fields.spec.ts`, `metadata-field.config.spec.ts`, `frontend/src/app/features/settings/metadata-settings/metadata-settings-component.spec.ts`, `frontend/src/app/features/metadata/model/request/metadata-refresh-options.model.spec.ts`, `frontend/src/app/shared/model/app-settings.model.spec.ts`

**Interfaces:**
- Produces: catalog descriptor `{ id: 'Litres', settingsKey: 'litres', labelKey: 'metadata.providers.litres', book: { ids: ['litresId'], rating: 'litresRating', reviewCount: 'litresReviewCount' } }`.
- `MetadataProviderSettings` gains `litres: Litres` with `enabled: boolean`.

- [x] **Step 1:** Add the descriptor at the end of the provider array (before AppleBooks or after — keep a stable order and update the spec expectation).
- [x] **Step 2:** Add `litresId`, `litresRating`, `litresReviewCount` and lock flags to both book models and clear flags.
- [x] **Step 3:** Add `litres` settings interface and property.
- [x] **Step 4:** Add `metadata.providers.litres: "LitRes"` to `en.json` and `"ЛитРес"` to `ru.json`.
- [x] **Step 5:** Update the five spec files' expected field arrays/objects.
- [x] **Step 6:** Run `cd frontend && pnpm test -- --run` (or the project's script) and expect PASS.
- [x] **Step 7:** Commit.

### Task 5: Full verification and live smoke test

- [x] **Step 1:** Run `cd backend && ./gradlew test --no-daemon` — expect PASS.
- [x] **Step 2:** Run frontend build/tests using `frontend/Justfile` commands — expect PASS.
- [x] **Step 3:** Run a live smoke script against `api.litres.ru` for `Дюна`, `Сергей Лукьяненко`, `Мастер и Маргарита`; assert non-empty results and print title/author/year for the first item.
- [x] **Step 4:** Summarize changed files, mapping decisions, and known gaps (UDC/BBK unavailable; translators stored in authors).
- [x] **Step 5:** Commit the design + plan + implementation.
