package org.booklore.service.metadata.parser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.dto.settings.MetadataProviderSettings;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.util.LanguageNormalizer;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Metadata provider for LitRes (litres.ru), the largest Russian ebook catalogue.
 *
 * <p>Uses LitRes' public JSON catalogue API instead of scraping HTML. The same
 * structure (search + details + retrying JSON request) can be reused when a
 * Litnet provider is added.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LitresParser implements BookParser, DetailedMetadataProvider {

    private static final String API_BASE = "https://api.litres.ru/foundation/api";
    private static final String SITE_BASE = "https://www.litres.ru";
    private static final String CDN_BASE = "https://cdn2.litres.ru";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final int CONNECTION_TIMEOUT_SECONDS = 15;
    private static final int MAX_RETRIES = 3;
    private static final int MAX_RESULTS = 10;
    private static final long MAX_RETRY_DELAY_MS = 30_000L;
    private static final Pattern NUMERIC_ID_PATTERN = Pattern.compile("\\d+");
    private static final Pattern ISBN_SEPARATOR_PATTERN = Pattern.compile("[^0-9Xx]");

    private final ObjectMapper objectMapper;
    private final AppSettingService appSettingService;
    private final HttpClient httpClient;

    private Optional<MetadataProviderSettings.Litres> getSettings() {
        var appSettings = appSettingService.getAppSettings();
        if (appSettings == null || appSettings.getMetadataProviderSettings() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(appSettings.getMetadataProviderSettings().getLitres());
    }

    @Override
    public boolean isEnabled() {
        return getSettings().map(MetadataProviderSettings.Litres::isEnabled).orElse(false);
    }

    @Override
    public List<BookMetadata> fetchMetadata(Book book, FetchMetadataRequest request) {
        if (!isEnabled()) {
            log.debug("LitRes provider is disabled");
            return List.of();
        }

        String title = trimToNull(request.getTitle());
        String author = trimToNull(request.getAuthor());
        String isbn = trimToNull(request.getIsbn());

        if (title == null && author == null && isbn == null) {
            log.debug("Empty search query for LitRes");
            return List.of();
        }

        log.info("Fetching LitRes metadata for book: {}", book != null ? book.getTitle() : title);

        List<BookMetadata> results = List.of();

        // Some catalogues index only one ISBN form (hyphenated or digits-only).
        if (isbn != null) {
            for (String isbnQuery : MetadataSearchUtils.isbnVariants(isbn)) {
                List<JsonNode> searchResults = search(isbnQuery);
                if (!searchResults.isEmpty()) {
                    results = hydrate(searchResults, isbn);
                    if (!results.isEmpty()) {
                        break;
                    }
                }
            }
        }

        if (results.isEmpty() && title != null) {
            List<String> titleQueries = new ArrayList<>();
            if (author != null) {
                titleQueries.add(title + " " + author);
            }
            titleQueries.add(title);
            for (String titleQuery : titleQueries) {
                List<JsonNode> searchResults = search(titleQuery);
                if (!searchResults.isEmpty()) {
                    results = filterRelevant(hydrate(searchResults, null), title, author);
                    if (!results.isEmpty()) {
                        break;
                    }
                }
            }
        }

        if (results.isEmpty() && author != null) {
            for (String authorQuery : MetadataSearchUtils.authorVariants(author)) {
                List<JsonNode> searchResults = search(authorQuery);
                if (!searchResults.isEmpty()) {
                    results = filterRelevant(hydrate(searchResults, null), null, author);
                    if (!results.isEmpty()) {
                        break;
                    }
                }
            }
        }

        if (results.isEmpty()) {
            log.info("No LitRes results found for query: {}", searchQuery(title, author, isbn));
            return List.of();
        }

        log.info("Found {} LitRes results for query: {}", results.size(), searchQuery(title, author, isbn));
        return results;
    }

    private List<BookMetadata> filterRelevant(List<BookMetadata> results, String title, String author) {
        if (title != null) {
            return results.stream()
                    .filter(metadata -> matchesText(metadata.getTitle(), title))
                    .limit(MAX_RESULTS)
                    .toList();
        }
        if (author != null) {
            return results.stream()
                    .filter(metadata -> authorMatches(metadata, author))
                    .limit(MAX_RESULTS)
                    .toList();
        }
        return results;
    }

    private boolean authorMatches(BookMetadata metadata, String author) {
        if (metadata.getAuthors() == null) {
            return false;
        }
        for (String variant : MetadataSearchUtils.authorVariants(author)) {
            if (metadata.getAuthors().stream().anyMatch(name -> matchesText(name, variant))) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesText(String haystack, String needle) {
        String normalizedHaystack = normalizeText(haystack);
        if (normalizedHaystack.isBlank()) {
            return false;
        }
        for (String token : normalizeText(needle).split(" ")) {
            if (token.isBlank()) {
                continue;
            }
            int stemLength = token.length() <= 3 ? token.length() : Math.min(4, token.length() - 1);
            if (!normalizedHaystack.contains(token.substring(0, stemLength))) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
    }

    private List<BookMetadata> hydrate(List<JsonNode> searchResults, String isbnToMatch) {
        List<BookMetadata> results = new ArrayList<>();
        Set<String> processedIds = new LinkedHashSet<>();
        for (JsonNode instance : searchResults) {
            if (results.size() >= MAX_RESULTS) {
                break;
            }
            String id = text(instance, "id");
            if (id == null || !processedIds.add(id)) {
                continue;
            }
            BookMetadata metadata = fetchDetailedMetadata(id);
            if (metadata == null) {
                continue;
            }
            if (isbnToMatch != null && !isbnMatches(metadata, isbnToMatch)) {
                log.debug("Skipping LitRes book {} because its ISBN does not match {}", id, isbnToMatch);
                continue;
            }
            results.add(metadata);
        }
        return results;
    }

    @Override
    public BookMetadata fetchTopMetadata(Book book, FetchMetadataRequest request) {
        List<BookMetadata> results = fetchMetadata(book, request);
        return results.isEmpty() ? null : results.getFirst();
    }

    @Override
    public BookMetadata fetchDetailedMetadata(String providerItemId) {
        if (!isEnabled() || providerItemId == null || !NUMERIC_ID_PATTERN.matcher(providerItemId.trim()).matches()) {
            return null;
        }

        JsonNode root = get(API_BASE + "/arts/" + providerItemId.trim());
        if (root == null) {
            return null;
        }
        JsonNode data = root.path("payload").path("data");
        if (!data.isObject()) {
            log.warn("Unexpected LitRes details response for id {}", providerItemId);
            return null;
        }
        return parseDetails(data);
    }

    private List<JsonNode> search(String query) {
        String url = UriComponentsBuilder.fromUriString(API_BASE + "/search")
                .queryParam("q", query)
                .queryParam("types", "text_book")
                .queryParam("limit", MAX_RESULTS)
                .build()
                .encode()
                .toUriString();
        log.debug("Searching LitRes: {}", url);

        JsonNode root = get(url);
        if (root == null) {
            return List.of();
        }
        JsonNode data = root.path("payload").path("data");
        if (!data.isArray()) {
            return List.of();
        }

        List<JsonNode> instances = new ArrayList<>();
        for (JsonNode item : data) {
            if (!"text_book".equals(text(item, "type"))) {
                continue;
            }
            JsonNode instance = item.path("instance");
            if (instance.isObject() && text(instance, "id") != null) {
                instances.add(instance);
            }
        }
        return instances;
    }

    private BookMetadata parseDetails(JsonNode data) {
        String id = text(data, "id");
        String title = text(data, "title");
        if (title == null) {
            log.warn("LitRes book {} has no title", id);
            return null;
        }

        Set<String> categories = new LinkedHashSet<>();
        collectNames(data.path("genres"), categories);
        Set<String> tags = new LinkedHashSet<>();
        collectNames(data.path("tags"), tags);

        BookMetadata.BookMetadataBuilder builder = BookMetadata.builder()
                .provider(MetadataProvider.Litres)
                .litresId(id)
                .title(title)
                .subtitle(text(data, "subtitle"))
                .authors(extractAuthors(data.path("persons")))
                .description(stripHtml(firstNonBlank(text(data, "html_annotation"),
                        text(data, "original_html_annotation"))))
                .thumbnailUrl(coverUrl(id, text(data, "cover_url")))
                .externalUrl(siteUrl(text(data, "url")))
                .categories(categories)
                .tags(tags)
                .publishedDate(parseDate(text(data, "publication_date"),
                        text(data, "date_written_at")))
                .language(LanguageNormalizer.normalize(text(data, "language_code")))
                .publisher(publisherName(data))
                .litresRating(doubleValue(data.path("rating"), "rated_avg"))
                .litresReviewCount(integerValue(data.path("rating"), "rated_total_count",
                        integerValue(data, "reviews_count")))
                .pageCount(pageCount(data));

        applyIsbn(builder, text(data, "isbn"));
        applySeries(builder, data.path("series"));

        return builder.build();
    }

    private void applyIsbn(BookMetadata.BookMetadataBuilder builder, String isbn) {
        if (isbn == null) {
            return;
        }
        String cleaned = ISBN_SEPARATOR_PATTERN.matcher(isbn).replaceAll("");
        if (cleaned.endsWith("x")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1) + "X";
        }
        if (cleaned.length() == 13) {
            builder.isbn13(cleaned);
        } else if (cleaned.length() == 10) {
            builder.isbn10(cleaned);
        }
    }

    private void applySeries(BookMetadata.BookMetadataBuilder builder, JsonNode series) {
        if (!series.isArray() || series.isEmpty()) {
            return;
        }
        JsonNode first = series.get(0);
        builder.seriesName(text(first, "name"));
        Integer order = integerValue(first, "art_order");
        if (order != null && order > 0) {
            builder.seriesNumber(order.floatValue());
        }
        Integer booksTotal = integerValue(first, "arts_count");
        if (booksTotal != null && booksTotal > 0) {
            builder.seriesTotal(booksTotal);
        }
    }

    private List<String> extractAuthors(JsonNode persons) {
        if (!persons.isArray()) {
            return List.of();
        }
        Set<String> authors = new LinkedHashSet<>();
        Set<String> translators = new LinkedHashSet<>();
        for (JsonNode person : persons) {
            String role = text(person, "role");
            String name = text(person, "full_name");
            if (name == null) {
                continue;
            }
            if ("author".equals(role)) {
                authors.add(name);
            } else if ("translator".equals(role)) {
                translators.add(name);
            }
        }
        // Grimmory has no dedicated translator field; the Douban provider follows
        // the same convention and appends translators to the author list.
        authors.addAll(translators);
        return new ArrayList<>(authors);
    }

    private void collectNames(JsonNode items, Set<String> target) {
        if (!items.isArray()) {
            return;
        }
        for (JsonNode item : items) {
            String name = text(item, "name");
            if (name != null) {
                target.add(name);
            }
        }
    }

    private String publisherName(JsonNode data) {
        String publisher = text(data.path("publisher"), "name");
        return publisher != null ? publisher : text(data.path("copyrighter"), "name");
    }

    private Integer pageCount(JsonNode data) {
        Integer pages = integerValue(data.path("additional_info"), "current_pages_or_seconds");
        return pages != null && pages > 0 ? pages : null;
    }

    private String coverUrl(String id, String coverPath) {
        if (coverPath != null) {
            return coverPath.startsWith("http") ? coverPath : CDN_BASE + coverPath;
        }
        return id != null ? CDN_BASE + "/pub/c/cover/" + id + ".jpg" : null;
    }

    private String siteUrl(String path) {
        if (path == null) {
            return null;
        }
        return path.startsWith("http") ? path : SITE_BASE + (path.startsWith("/") ? path : "/" + path);
    }

    private LocalDate parseDate(String... values) {
        for (String value : values) {
            if (value == null) {
                continue;
            }
            try {
                return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
            } catch (DateTimeParseException e) {
                log.debug("Unable to parse LitRes date: {}", value);
            }
        }
        return null;
    }

    private String stripHtml(String html) {
        if (html == null) {
            return null;
        }
        String text = Jsoup.parse(html).body().wholeText()
                .replace('\u00a0', ' ')
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return text.isEmpty() ? null : text;
    }

    private boolean isbnMatches(BookMetadata metadata, String searchIsbn) {
        String normalizedSearch = ISBN_SEPARATOR_PATTERN.matcher(searchIsbn).replaceAll("");
        if (normalizedSearch.endsWith("x")) {
            normalizedSearch = normalizedSearch.substring(0, normalizedSearch.length() - 1) + "X";
        }
        return normalizedSearch.equals(metadata.getIsbn13()) || normalizedSearch.equals(metadata.getIsbn10());
    }

    private JsonNode get(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(CONNECTION_TIMEOUT_SECONDS))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Accept-Language", "ru-RU,ru;q=0.9")
                .header("Referer", SITE_BASE + "/")
                .GET()
                .build();

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                int status = response.statusCode();

                if (status >= 200 && status < 300) {
                    JsonNode root;
                    try {
                        root = objectMapper.readTree(response.body());
                    } catch (RuntimeException e) {
                        log.error("Failed to parse LitRes JSON response from {}", url, e);
                        return null;
                    }

                    int apiStatus = root.path("status").asInt(200);
                    if (apiStatus >= 200 && apiStatus < 300) {
                        return root;
                    }
                    if (isRetryable(apiStatus) && attempt < MAX_RETRIES) {
                        long delay = retryDelayMillis(response, attempt);
                        log.warn("LitRes API returned status {} (attempt {}/{}), retrying in {} ms",
                                apiStatus, attempt, MAX_RETRIES, delay);
                        if (!sleep(delay)) {
                            return null;
                        }
                        continue;
                    }

                    log.error("LitRes API returned status {} for {}", apiStatus, url);
                    return null;
                }
                if (status == 404) {
                    log.debug("LitRes resource not found: {}", url);
                    return null;
                }
                if (isRetryable(status) && attempt < MAX_RETRIES) {
                    long delay = retryDelayMillis(response, attempt);
                    log.warn("LitRes request failed with status {} (attempt {}/{}), retrying in {} ms",
                            status, attempt, MAX_RETRIES, delay);
                    if (!sleep(delay)) {
                        return null;
                    }
                    continue;
                }

                log.error("LitRes request failed with status {} for {}", status, url);
                return null;
            } catch (IOException e) {
                if (attempt < MAX_RETRIES) {
                    log.warn("LitRes request failed (attempt {}/{}): {}", attempt, MAX_RETRIES, e.getMessage());
                    if (!sleep(1000L * attempt)) {
                        return null;
                    }
                } else {
                    log.error("Failed to query LitRes after {} attempts", MAX_RETRIES, e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("LitRes request interrupted");
                return null;
            }
        }
        return null;
    }

    private boolean isRetryable(int status) {
        return status == 403 || status == 429 || status >= 500;
    }

    private long retryDelayMillis(HttpResponse<String> response, int attempt) {
        return Math.min(MAX_RETRY_DELAY_MS,
                retryAfterMillis(response).orElse(1000L * attempt));
    }

    private Optional<Long> retryAfterMillis(HttpResponse<String> response) {
        return response.headers()
                .firstValue("Retry-After")
                .map(value -> {
                    try {
                        return Math.max(0L, Long.parseLong(value.trim()) * 1000L);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                });
    }

    private boolean sleep(long millis) {
        if (millis <= 0) {
            return true;
        }
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String searchQuery(String title, String author, String isbn) {
        if (isbn != null) {
            return isbn;
        }
        if (title != null && author != null) {
            return title + " " + author;
        }
        return title != null ? title : author;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        return text(node.get(field));
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return trimToNull(node.asText());
    }

    private static Double doubleValue(JsonNode node, String field) {
        JsonNode value = node != null ? node.get(field) : null;
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        if (value.isTextual()) {
            try {
                return Double.parseDouble(value.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Integer integerValue(JsonNode node, String field) {
        return integerValue(node, field, null);
    }

    private static Integer integerValue(JsonNode node, String field, Integer fallback) {
        JsonNode value = node != null ? node.get(field) : null;
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (value.isNumber()) {
            return value.asInt();
        }
        if (value.isTextual()) {
            try {
                return Integer.parseInt(value.asText().trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }
}
