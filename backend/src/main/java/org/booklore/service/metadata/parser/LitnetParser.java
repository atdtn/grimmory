package org.booklore.service.metadata.parser;

import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.dto.settings.MetadataProviderSettings;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.service.appsettings.AppSettingService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Metadata provider for litnet.com.
 *
 * <p>Litnet is an Angular SSR application with no public JSON API. Search and
 * details are read from the embedded {@code ng-state} transfer-state script;
 * the detail page's JSON-LD Book block is used as a fallback for ratings and
 * fields missing from the state. The site sits behind Cloudflare, so requests
 * use browser headers, a small cookie jar, retries, and a 1-second politeness
 * interval.</p>
 *
 * <p>Litnet has no ISBN data, so ISBN-only searches return no results.</p>
 */
@Service
@Slf4j
public class LitnetParser implements BookParser, DetailedMetadataProvider {

    private static final String SITE_BASE = "https://litnet.com";
    private static final String SEARCH_URL = SITE_BASE + "/ru/search";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final int REQUEST_TIMEOUT_SECONDS = 15;
    private static final int MAX_RETRIES = 3;
    private static final int MAX_RESULTS = 10;
    private static final int MAX_HYDRATED_RESULTS = 3;
    private static final long DEFAULT_REQUEST_INTERVAL_MS = 1_000L;
    private static final long MAX_RETRY_DELAY_MS = 30_000L;
    private static final Pattern NUMERIC_ID_PATTERN = Pattern.compile("\\d+");

    private final ObjectMapper objectMapper;
    private final AppSettingService appSettingService;
    private final HttpClient httpClient;
    private final long requestIntervalMs;
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private long nextRequestAt;

    @Autowired
    public LitnetParser(ObjectMapper objectMapper,
                        AppSettingService appSettingService,
                        HttpClient httpClient) {
        this(objectMapper, appSettingService, httpClient, DEFAULT_REQUEST_INTERVAL_MS);
    }

    LitnetParser(ObjectMapper objectMapper,
                 AppSettingService appSettingService,
                 HttpClient httpClient,
                 long requestIntervalMs) {
        this.objectMapper = objectMapper;
        this.appSettingService = appSettingService;
        this.httpClient = httpClient;
        this.requestIntervalMs = Math.max(0L, requestIntervalMs);
    }

    private Optional<MetadataProviderSettings.Litnet> getSettings() {
        var appSettings = appSettingService.getAppSettings();
        if (appSettings == null || appSettings.getMetadataProviderSettings() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(appSettings.getMetadataProviderSettings().getLitnet());
    }

    @Override
    public boolean isEnabled() {
        return getSettings().map(MetadataProviderSettings.Litnet::isEnabled).orElse(false);
    }

    @Override
    public List<BookMetadata> fetchMetadata(Book book, FetchMetadataRequest request) {
        if (!isEnabled()) {
            log.debug("Litnet provider is disabled");
            return List.of();
        }

        String title = trimToNull(request.getTitle());
        String author = trimToNull(request.getAuthor());
        String isbn = trimToNull(request.getIsbn());
        if (title == null && author == null) {
            if (isbn != null) {
                log.info("Litnet does not expose ISBN search; skipping ISBN-only query {}", isbn);
            } else {
                log.debug("Empty search query for Litnet");
            }
            return List.of();
        }

        log.info("Fetching Litnet metadata for book: {}",
                book != null ? book.getTitle() : title);
        String query = title != null
                ? (author != null ? title + " " + author : title)
                : author;
        List<JsonNode> books = search(query);
        if (books.isEmpty()) {
            log.info("No Litnet results found for query: {}", query);
            return List.of();
        }

        List<BookMetadata> mapped = books.stream()
                .map(this::mapSearchBook)
                .filter(metadata -> metadata != null)
                .toList();
        List<BookMetadata> filtered = mapped.stream()
                .filter(metadata -> matchesSearch(metadata, title, author))
                .limit(MAX_RESULTS)
                .toList();
        List<BookMetadata> results = filtered.isEmpty()
                ? mapped.stream().limit(MAX_RESULTS).toList()
                : filtered;

        // Search results expose the uploader as the author and omit the average
        // rating, publisher and cycle. Hydrate a bounded number of results from
        // the detail page so the normal picker/refresh flow gets real metadata.
        List<BookMetadata> hydrated = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            BookMetadata result = results.get(i);
            if (i < MAX_HYDRATED_RESULTS && result.getLitnetId() != null) {
                BookMetadata details = fetchDetailedMetadata(result.getLitnetId());
                hydrated.add(details != null ? details : result);
            } else {
                hydrated.add(result);
            }
        }

        log.info("Found {} Litnet results for query: {}", hydrated.size(), query);
        return hydrated;
    }

    @Override
    public BookMetadata fetchTopMetadata(Book book, FetchMetadataRequest request) {
        List<BookMetadata> results = fetchMetadata(book, request);
        return results.isEmpty() ? null : results.getFirst();
    }

    @Override
    public BookMetadata fetchDetailedMetadata(String providerItemId) {
        if (!isEnabled() || providerItemId == null
                || !NUMERIC_ID_PATTERN.matcher(providerItemId.trim()).matches()) {
            return null;
        }

        String id = providerItemId.trim();
        // The slug before "-b{id}" is not validated by Litnet; "x" avoids the
        // extra redirect that a bare "-b{id}" URL produces.
        String html = getHtml(SITE_BASE + "/ru/book/x-b" + id);
        if (html == null || html.isBlank()) {
            log.warn("Litnet details response was empty for id {}", id);
            return null;
        }

        try {
            Document document = Jsoup.parse(html);
            JsonNode book = findBookNode(readNgState(document), id);
            JsonNode jsonLd = findBookJsonLd(document);
            if (book == null && jsonLd == null) {
                log.warn("Litnet details page had neither ng-state book nor JSON-LD for id {}", id);
                return null;
            }
            return parseDetails(id, book, jsonLd);
        } catch (RuntimeException e) {
            log.warn("Failed to parse Litnet details for id {}", id, e);
            return null;
        }
    }

    private List<JsonNode> search(String query) {
        String url = UriComponentsBuilder.fromUriString(SEARCH_URL)
                .queryParam("q", query)
                .build()
                .encode()
                .toUriString();
        log.debug("Searching Litnet: {}", url);

        String html = getHtml(url);
        if (html == null || html.isBlank()) {
            return List.of();
        }
        try {
            JsonNode state = readNgState(Jsoup.parse(html));
            if (state == null) {
                return List.of();
            }
            JsonNode items = null;
            for (Map.Entry<String, JsonNode> entry : state.properties()) {
                if (entry.getKey().startsWith("items-books-")) {
                    items = entry.getValue();
                    break;
                }
            }
            if (items == null || !items.isArray()) {
                return List.of();
            }
            List<JsonNode> books = new ArrayList<>();
            for (JsonNode item : items) {
                if (item.isObject()) {
                    books.add(item);
                }
            }
            return books;
        } catch (RuntimeException e) {
            log.warn("Failed to parse Litnet search page", e);
            return List.of();
        }
    }

    private BookMetadata mapSearchBook(JsonNode item) {
        String id = text(item, "id");
        String title = text(item, "title");
        if (id == null || title == null) {
            return null;
        }

        Set<String> categories = new LinkedHashSet<>();
        collectNames(item.path("genreList"), categories);
        Set<String> tags = new LinkedHashSet<>();
        collectNames(item.path("tagList"), tags);

        List<String> authors = new ArrayList<>();
        addName(authors, item.path("author"), "name");
        addName(authors, item.path("coAuthor"), "name");

        Integer reviewCount = integerValue(item, "rating");
        return BookMetadata.builder()
                .provider(MetadataProvider.LITNET)
                .litnetId(id)
                .title(title)
                .authors(authors)
                .description(stripHtml(text(item, "description")))
                .thumbnailUrl(originalCover(text(item, "image")))
                .externalUrl(text(item, "link"))
                .publishedDate(parseDate(text(item, "firstPublicationDate")))
                .pageCount(integerValue(item, "countOfPages"))
                .language("ru")
                .categories(categories)
                .tags(tags)
                .litnetReviewCount(reviewCount != null && reviewCount > 0 ? reviewCount : null)
                .build();
    }

    private BookMetadata parseDetails(String id, JsonNode book, JsonNode jsonLd) {
        String title = book != null ? text(book, "title") : text(jsonLd, "name");
        if (title == null) {
            return null;
        }

        List<String> authors = new ArrayList<>();
        if (book != null) {
            addName(authors, book.path("author"), "name");
            JsonNode coAuthor = book.has("co_author") ? book.path("co_author") : book.path("coAuthor");
            if (coAuthor.isTextual()) {
                addIfNotBlank(authors, coAuthor.asText());
            } else {
                addName(authors, coAuthor, "name");
            }
        }
        if (authors.isEmpty()) {
            addJsonLdAuthors(authors, jsonLd);
        }

        String description = firstNonBlank(
                book != null ? stripHtml(text(book, "annotation")) : null,
                stripHtml(text(jsonLd, "description")));
        String image = firstNonBlank(
                book != null ? firstNonBlank(text(book, "image_original"), text(book, "imageOriginal"),
                        text(book, "image")) : null,
                text(jsonLd, "image"));
        String publishedAt = firstNonBlank(
                book != null ? firstNonBlank(text(book, "published_at"), text(book, "firstPublicationDate")) : null,
                text(jsonLd, "datePublished"));

        Set<String> categories = new LinkedHashSet<>();
        if (book != null) {
            collectNames(book.path("genres"), categories);
            if (categories.isEmpty()) {
                collectNames(book.path("genreList"), categories);
            }
        }
        if (categories.isEmpty() && jsonLd != null) {
            collectGenreField(jsonLd.path("genre"), categories);
        }
        Set<String> tags = new LinkedHashSet<>();
        if (book != null) {
            collectNames(book.path("tags"), tags);
            if (tags.isEmpty()) {
                collectNames(book.path("tagList"), tags);
            }
        }

        JsonNode aggregateRating = jsonLd != null ? jsonLd.path("aggregateRating") : null;
        Double rating = doubleValue(aggregateRating, "ratingValue");
        Integer reviewCount = integerValue(aggregateRating, "ratingCount");
        if (reviewCount == null && book != null) {
            reviewCount = firstNonNull(
                    integerValue(book.path("stats"), "rating"),
                    integerValue(book, "rating"));
        }

        String externalUrl = firstNonBlank(text(jsonLd, "url"), text(book, "link"));
        if (externalUrl == null) {
            externalUrl = SITE_BASE + "/ru/book/x-b" + id;
        }

        BookMetadata.BookMetadataBuilder builder = BookMetadata.builder()
                .provider(MetadataProvider.LITNET)
                .litnetId(id)
                .title(title)
                .authors(authors)
                .description(description)
                .thumbnailUrl(originalCover(image))
                .externalUrl(externalUrl)
                .publishedDate(parseDate(publishedAt))
                .language("ru")
                .categories(categories)
                .tags(tags)
                .litnetRating(rating != null && rating > 0 ? rating : null)
                .litnetReviewCount(reviewCount != null && reviewCount > 0 ? reviewCount : null);

        if (book != null) {
            builder.pageCount(firstNonNull(
                            integerValue(book, "pages"),
                            integerValue(book, "countOfPages")))
                    .publisher(firstNonBlank(
                            text(book.path("publisher"), "publisher_title"),
                            text(book.path("publisher"), "title")))
                    .seriesName(cycleName(book.path("cycle")));
            Integer cycleNumber = firstNonNull(
                    integerValue(book, "priority_cycle"),
                    integerValue(book, "priorityInCycle"));
            if (cycleNumber != null && cycleNumber > 0) {
                builder.seriesNumber(cycleNumber.floatValue());
            }
        }

        return builder.build();
    }

    private JsonNode readNgState(Document document) {
        Element script = document.selectFirst("script#ng-state");
        if (script == null) {
            return null;
        }
        return objectMapper.readTree(script.data());
    }

    private JsonNode findBookNode(JsonNode state, String id) {
        if (state == null) {
            return null;
        }
        long numericId;
        try {
            numericId = Long.parseLong(id);
        } catch (NumberFormatException e) {
            return null;
        }
        JsonNode preferred = findBookNode(state, numericId, 0, true);
        return preferred != null ? preferred : findBookNode(state, numericId, 0, false);
    }

    /**
     * The page state can contain the same book twice: the transfer-cache node
     * (snake_case, richest fields) and the page/card node (camelCase). Prefer
     * the transfer-cache shape, then fall back to any book-shaped node.
     */
    private JsonNode findBookNode(JsonNode node, long id, int depth, boolean requireTransferCacheShape) {
        if (node == null || depth > 8) {
            return null;
        }
        if (node.isObject()) {
            JsonNode idNode = node.get("id");
            if (idNode != null && idNode.canConvertToLong() && idNode.asLong() == id
                    && node.has("title")
                    && (!requireTransferCacheShape || node.has("pages"))) {
                return node;
            }
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                JsonNode found = findBookNode(entry.getValue(), id, depth + 1, requireTransferCacheShape);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = findBookNode(child, id, depth + 1, requireTransferCacheShape);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private JsonNode findBookJsonLd(Document document) {
        for (Element script : document.select("script[type=application/ld+json]")) {
            try {
                JsonNode root = objectMapper.readTree(script.data());
                JsonNode candidate = root;
                if (root.isArray()) {
                    for (JsonNode item : root) {
                        if (isBookJsonLd(item)) {
                            candidate = item;
                            break;
                        }
                    }
                }
                if (isBookJsonLd(candidate)) {
                    return candidate;
                }
            } catch (RuntimeException e) {
                log.debug("Skipping unparseable Litnet JSON-LD block");
            }
        }
        return null;
    }

    private boolean isBookJsonLd(JsonNode node) {
        return node != null && node.isObject()
                && node.path("@type").asText("").equalsIgnoreCase("Book");
    }

    private String originalCover(String image) {
        if (image == null) {
            return null;
        }
        return image.replace("/covers/220/", "/covers/0/");
    }

    private String cycleName(JsonNode cycle) {
        if (cycle == null || cycle.isNull() || cycle.isMissingNode()) {
            return null;
        }
        if (cycle.isTextual()) {
            return trimToNull(cycle.asText());
        }
        return firstNonBlank(text(cycle, "name"), text(cycle, "title"));
    }

    private boolean matchesSearch(BookMetadata metadata, String title, String author) {
        if (title != null && !matchesText(metadata.getTitle(), title)) {
            return false;
        }
        if (author != null) {
            return metadata.getAuthors() != null && metadata.getAuthors().stream()
                    .anyMatch(name -> matchesText(name, author));
        }
        return true;
    }

    private void collectNames(JsonNode items, Set<String> target) {
        if (items == null || !items.isArray()) {
            return;
        }
        for (JsonNode item : items) {
            String name = text(item, "name");
            if (name != null) {
                target.add(name);
            }
        }
    }

    private void collectGenreField(JsonNode genre, Set<String> target) {
        if (genre == null || genre.isNull()) {
            return;
        }
        if (genre.isArray()) {
            for (JsonNode item : genre) {
                collectGenreField(item, target);
            }
        } else {
            String value = trimToNull(genre.asText());
            if (value != null) {
                target.add(value);
            }
        }
    }

    private void addJsonLdAuthors(List<String> authors, JsonNode jsonLd) {
        if (jsonLd == null) {
            return;
        }
        JsonNode jsonAuthor = jsonLd.path("author");
        if (jsonAuthor.isArray()) {
            for (JsonNode author : jsonAuthor) {
                addName(authors, author, "name");
            }
        } else {
            addName(authors, jsonAuthor, "name");
        }
    }

    private void addName(List<String> authors, JsonNode person, String field) {
        String name = text(person, field);
        addIfNotBlank(authors, name);
    }

    private void addIfNotBlank(List<String> values, String value) {
        if (value != null && !value.isBlank() && !values.contains(value)) {
            values.add(value);
        }
    }

    private String getHtml(String url) {
        return executeRequest(url);
    }

    private synchronized String executeRequest(String url) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                if (!waitForRequestInterval()) {
                    return null;
                }
                HttpRequest request = buildRequest(url);
                HttpResponse<String> response;
                try {
                    response = httpClient.send(
                            request,
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                    );
                } finally {
                    markRequestFinished();
                }
                storeCookies(response);
                int status = response.statusCode();
                if (status >= 200 && status < 300 && !isCloudflareChallenge(response.body())) {
                    return response.body();
                }
                if (status == 404) {
                    log.debug("Litnet resource not found: {}", url);
                    return null;
                }
                if ((isRetryable(status) || isCloudflareChallenge(response.body())) && attempt < MAX_RETRIES) {
                    long delay = retryDelayMillis(response, attempt);
                    log.warn("Litnet request failed with status {} (attempt {}/{}), retrying in {} ms",
                            status, attempt, MAX_RETRIES, delay);
                    if (!sleep(delay)) {
                        return null;
                    }
                    continue;
                }

                log.error("Litnet request failed with status {} for {}", status, url);
                return null;
            } catch (IOException e) {
                if (attempt < MAX_RETRIES) {
                    log.warn("Litnet request failed (attempt {}/{}): {}", attempt, MAX_RETRIES, e.getMessage());
                    if (!sleep(1000L * attempt)) {
                        return null;
                    }
                } else {
                    log.error("Failed to query Litnet after {} attempts", MAX_RETRIES, e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Litnet request interrupted");
                return null;
            }
        }
        return null;
    }

    private HttpRequest buildRequest(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ru-RU,ru;q=0.9")
                .header("Referer", SITE_BASE + "/");
        if (!cookies.isEmpty()) {
            builder.header("Cookie", cookieHeader());
        }
        return builder.GET().build();
    }

    private boolean waitForRequestInterval() {
        if (requestIntervalMs <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        return now >= nextRequestAt || sleep(nextRequestAt - now);
    }

    private void markRequestFinished() {
        if (requestIntervalMs > 0) {
            nextRequestAt = System.currentTimeMillis() + requestIntervalMs;
        }
    }

    private boolean isCloudflareChallenge(String body) {
        return body != null && (body.contains("Just a moment...")
                || body.contains("cf-chl") || body.contains("cf_chl_opt"));
    }

    private long retryDelayMillis(HttpResponse<String> response, int attempt) {
        return Math.min(MAX_RETRY_DELAY_MS,
                response.headers().firstValue("Retry-After")
                        .map(value -> {
                            try {
                                return Math.max(0L, Long.parseLong(value.trim()) * 1000L);
                            } catch (NumberFormatException e) {
                                return null;
                            }
                        })
                        .orElse(1000L * attempt));
    }

    private boolean isRetryable(int status) {
        return status == 403 || status == 429 || status >= 500;
    }

    private void storeCookies(HttpResponse<String> response) {
        if (response.headers() == null) {
            return;
        }
        for (String header : response.headers().allValues("Set-Cookie")) {
            int separator = header.indexOf(';');
            String pair = separator >= 0 ? header.substring(0, separator) : header;
            int equals = pair.indexOf('=');
            if (equals > 0) {
                cookies.put(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
            }
        }
    }

    private String cookieHeader() {
        return cookies.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
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

    private LocalDate parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String stripHtml(String html) {
        if (html == null) {
            return null;
        }
        String text = Jsoup.parse(html).text()
                .replace('\u00a0', ' ')
                .replaceAll("[ \\t]+", " ")
                .trim();
        return text.isEmpty() ? null : text;
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
                return Double.parseDouble(value.asText().trim().replace(',', '.'));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Integer integerValue(JsonNode node, String field) {
        JsonNode value = node != null ? node.get(field) : null;
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asInt();
        }
        if (value.isTextual()) {
            try {
                return Integer.parseInt(value.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
}
