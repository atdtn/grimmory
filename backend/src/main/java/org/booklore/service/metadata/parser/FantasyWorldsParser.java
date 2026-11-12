package org.booklore.service.metadata.parser;

import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.dto.settings.MetadataProviderSettings;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.util.LanguageNormalizer;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Metadata provider for fantasy-worlds.org.
 *
 * <p>The site exposes a public JSON search endpoint ({@code /search.json}) and
 * an HTML details page with schema.org microdata. Search results already carry
 * almost all metadata, so only the results list is requested during a search;
 * details are fetched on demand through {@link DetailedMetadataProvider}.</p>
 *
 * <p>The site's robots.txt requests {@code Crawl-delay: 10}. Every request to
 * the host is therefore throttled to one request per ten seconds.</p>
 */
@Service
@Slf4j
public class FantasyWorldsParser implements BookParser, DetailedMetadataProvider {

    private static final String SITE_BASE = "https://fantasy-worlds.org";
    private static final String SEARCH_URL = SITE_BASE + "/search.json";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final int REQUEST_TIMEOUT_SECONDS = 15;
    private static final int MAX_RETRIES = 3;
    private static final int MAX_RESULTS = 10;
    private static final long DEFAULT_CRAWL_DELAY_MS = 10_000L;
    private static final long MAX_RETRY_DELAY_MS = 30_000L;
    private static final Pattern NUMERIC_ID_PATTERN = Pattern.compile("\\d+");
    private static final Pattern ISBN_SEPARATOR_PATTERN = Pattern.compile("[^0-9Xx]");
    private static final Pattern RATIO_PATTERN = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*/\\s*(\\d+)");
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(1[0-9]{3}|20[0-9]{2})\\b");
    private static final Pattern SERIES_NUMBER_PATTERN =
            Pattern.compile("Номер книги в серии:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;
    private final AppSettingService appSettingService;
    private final HttpClient httpClient;
    private final long crawlDelayMs;
    private long nextRequestAt;

    @Autowired
    public FantasyWorldsParser(ObjectMapper objectMapper,
                               AppSettingService appSettingService,
                               HttpClient httpClient) {
        this(objectMapper, appSettingService, httpClient, DEFAULT_CRAWL_DELAY_MS);
    }

    FantasyWorldsParser(ObjectMapper objectMapper,
                        AppSettingService appSettingService,
                        HttpClient httpClient,
                        long crawlDelayMs) {
        this.objectMapper = objectMapper;
        this.appSettingService = appSettingService;
        this.httpClient = httpClient;
        this.crawlDelayMs = Math.max(0L, crawlDelayMs);
    }

    private Optional<MetadataProviderSettings.FantasyWorlds> getSettings() {
        var appSettings = appSettingService.getAppSettings();
        if (appSettings == null || appSettings.getMetadataProviderSettings() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(appSettings.getMetadataProviderSettings().getFantasyWorlds());
    }

    @Override
    public boolean isEnabled() {
        return getSettings().map(MetadataProviderSettings.FantasyWorlds::isEnabled).orElse(false);
    }

    @Override
    public List<BookMetadata> fetchMetadata(Book book, FetchMetadataRequest request) {
        if (!isEnabled()) {
            log.debug("Fantasy Worlds provider is disabled");
            return List.of();
        }

        String title = trimToNull(request.getTitle());
        String author = trimToNull(request.getAuthor());
        String isbn = trimToNull(request.getIsbn());
        if (title == null && author == null && isbn == null) {
            log.debug("Empty search query for Fantasy Worlds");
            return List.of();
        }

        log.info("Fetching Fantasy Worlds metadata for book: {}",
                book != null ? book.getTitle() : title);

        List<BookMetadata> firstRawResults = null;
        for (String query : buildSearchQueries(title, author, isbn)) {
            List<JsonNode> books = search(query);
            if (books.isEmpty()) {
                continue;
            }

            List<BookMetadata> mapped = books.stream()
                    .map(this::mapSearchBook)
                    .filter(metadata -> metadata != null)
                    .toList();
            if (mapped.isEmpty()) {
                continue;
            }
            if (firstRawResults == null) {
                firstRawResults = mapped;
            }

            List<BookMetadata> filtered = mapped.stream()
                    .filter(metadata -> matchesSearch(metadata, title, author, isbn))
                    .limit(MAX_RESULTS)
                    .toList();
            if (!filtered.isEmpty()) {
                log.info("Found {} Fantasy Worlds results for query: {}", filtered.size(), query);
                return filtered;
            }
        }

        if (firstRawResults != null) {
            // The JSON search also matches authors and keywords. If strict
            // filtering removed everything, return the raw results rather than
            // hiding a potentially correct match.
            List<BookMetadata> results = firstRawResults.stream().limit(MAX_RESULTS).toList();
            log.info("Found {} Fantasy Worlds results for fallback query", results.size());
            return results;
        }

        log.info("No Fantasy Worlds results found for query: {}",
                title != null ? title : (author != null ? author : isbn));
        return List.of();
    }

    private List<String> buildSearchQueries(String title, String author, String isbn) {
        Set<String> queries = new LinkedHashSet<>();
        if (title != null && author != null) {
            queries.add(title + " " + author);
        }
        if (title != null) {
            queries.add(title);
        }
        if (author != null) {
            queries.addAll(MetadataSearchUtils.authorVariants(author));
        }
        if (isbn != null) {
            queries.addAll(MetadataSearchUtils.isbnVariants(isbn));
        }
        return new ArrayList<>(queries);
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
        String url = SITE_BASE + "/lib/id" + id + "/";
        String html = getHtml(url);
        if (html == null || html.isBlank()) {
            log.warn("Fantasy Worlds details response was empty for {}", url);
            return null;
        }

        try {
            BookMetadata metadata = parseDetails(Jsoup.parse(html), id);
            if (metadata == null) {
                log.warn("Fantasy Worlds details page contained no recognizable title for {}", url);
            }
            return metadata;
        } catch (RuntimeException e) {
            log.warn("Failed to parse Fantasy Worlds details for id {}", id, e);
            return null;
        }
    }

    private List<JsonNode> search(String query) {
        String url = UriComponentsBuilder.fromUriString(SEARCH_URL)
                .queryParam("q", query)
                .build()
                .encode()
                .toUriString();
        log.debug("Searching Fantasy Worlds: {}", url);

        JsonNode root = getJson(url);
        if (root == null) {
            return List.of();
        }
        JsonNode books = root.path("books");
        if (!books.isArray()) {
            return List.of();
        }

        List<JsonNode> results = new ArrayList<>();
        for (JsonNode book : books) {
            if (book.isObject()) {
                results.add(book);
            }
        }
        return results;
    }

    private boolean matchesSearch(BookMetadata metadata, String title, String author, String isbn) {
        if (isbn != null) {
            String normalized = normalizeIsbn(isbn);
            if (normalized != null
                    && (normalized.equals(metadata.getIsbn13()) || normalized.equals(metadata.getIsbn10()))) {
                return true;
            }
        }
        if (title != null) {
            return matchesText(metadata.getTitle(), title);
        }
        if (author != null) {
            return authorMatches(metadata, author);
        }
        return false;
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

    private BookMetadata mapSearchBook(JsonNode book) {
        String id = text(book, "id");
        String title = text(book, "title");
        if (id == null || title == null) {
            return null;
        }

        String bookExt = text(book, "book_ext");
        Ratio ratio = parseRatio(text(book, "ratio"));

        BookMetadata.BookMetadataBuilder builder = BookMetadata.builder()
                .provider(MetadataProvider.FANTASY_WORLDS)
                .fantasyWorldsId(id)
                .title(title)
                .authors(extractAuthors(book))
                .description(stripHtml(text(book, "about")))
                .publishedDate(parseYear(text(book, "year")))
                .seriesName(blankToNull(text(book, "series_name")))
                .seriesNumber(seriesNumber(book))
                .language(LanguageNormalizer.normalize(text(book, "lang_code")))
                .thumbnailUrl(coverUrl(id, bookExt))
                .externalUrl(bookUrl(id))
                .fantasyWorldsRating(ratio.rating())
                .fantasyWorldsReviewCount(ratio.count())
                .categories(genreCategories(text(book, "genre")));

        applyIsbn(builder, text(book, "ISBN"));
        return builder.build();
    }

    private BookMetadata parseDetails(Document doc, String id) {
        String title = text(doc.selectFirst("[itemprop=name]"));
        if (title == null) {
            String ogTitle = text(doc.selectFirst("meta[property=og:title]"));
            title = ogTitle != null && ogTitle.contains(" - ")
                    ? trimToNull(ogTitle.substring(ogTitle.lastIndexOf(" - ") + 3))
                    : ogTitle;
        }
        if (title == null) {
            return null;
        }

        Set<String> authors = new LinkedHashSet<>();
        for (Element author : doc.select("a[itemprop=author]")) {
            String name = trimToNull(author.text());
            if (name != null) {
                authors.add(name);
            }
        }

        Ratio ratio = parseDetailRating(doc);
        String cover = text(doc.selectFirst("meta[property=og:image]"));
        String isbn = text(doc.selectFirst("[itemprop=isbn]"));
        if (isbn == null) {
            isbn = text(doc.selectFirst("meta[property=books:isbn]"));
        }

        String pageText = doc.text();
        Matcher seriesMatcher = SERIES_NUMBER_PATTERN.matcher(pageText);
        Float seriesNumber = seriesMatcher.find() ? parseFloat(seriesMatcher.group(1)) : null;
        if (seriesNumber != null && seriesNumber <= 0) {
            seriesNumber = null;
        }

        Set<String> tags = new LinkedHashSet<>();
        for (Element tag : doc.select("a[href^=/lib/tag]")) {
            String name = trimToNull(tag.text());
            if (name != null) {
                tags.add(name);
            }
        }

        BookMetadata.BookMetadataBuilder builder = BookMetadata.builder()
                .provider(MetadataProvider.FANTASY_WORLDS)
                .fantasyWorldsId(id)
                .title(title)
                .authors(new ArrayList<>(authors))
                .description(stripHtml(text(doc.selectFirst("[itemprop=description]"))))
                .publishedDate(parseYear(extractLabeledValue(pageText, "Год")))
                .seriesName(extractSeriesName(doc))
                .seriesNumber(seriesNumber)
                .language(LanguageNormalizer.normalize(text(doc.selectFirst("meta[itemprop=inLanguage]"))))
                .thumbnailUrl(fullCoverUrl(cover, id))
                .externalUrl(bookUrl(id))
                .fantasyWorldsRating(ratio.rating())
                .fantasyWorldsReviewCount(ratio.count())
                .tags(tags);

        applyIsbn(builder, isbn);
        return builder.build();
    }

    private List<String> extractAuthors(JsonNode book) {
        String name = text(book, "author_name");
        String surname = text(book, "author_surname");
        String fullName = trimToNull((name != null ? name : "") + " " + (surname != null ? surname : ""));
        return fullName == null ? List.of() : List.of(fullName);
    }

    private Float seriesNumber(JsonNode book) {
        Float number = parseFloat(text(book, "serNumb"));
        if (number == null || number <= 0) {
            number = parseFloat(text(book, "series_order"));
        }
        return number != null && number > 0 ? number : null;
    }

    private Set<String> genreCategories(String genreId) {
        if (genreId == null || "0".equals(genreId)) {
            return Set.of();
        }
        // The site exposes only numeric genre ids; there is no public genre
        // lookup, so the id is preserved in a stable, readable form.
        return Set.of("genre:" + genreId);
    }

    private void applyIsbn(BookMetadata.BookMetadataBuilder builder, String rawIsbn) {
        String isbn = normalizeIsbn(rawIsbn);
        if (isbn == null) {
            return;
        }
        if (isbn.length() == 13) {
            builder.isbn13(isbn);
        } else if (isbn.length() == 10) {
            builder.isbn10(isbn);
        }
    }

    /**
     * Returns the first valid ISBN from a raw value that may contain several
     * comma-separated ISBNs with leading spaces.
     */
    private String normalizeIsbn(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (String candidate : raw.split(",")) {
            String cleaned = ISBN_SEPARATOR_PATTERN.matcher(candidate).replaceAll("");
            if (cleaned.endsWith("x")) {
                cleaned = cleaned.substring(0, cleaned.length() - 1) + "X";
            }
            if (cleaned.length() == 13 || cleaned.length() == 10) {
                return cleaned;
            }
        }
        return null;
    }

    private LocalDate parseYear(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = YEAR_PATTERN.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        try {
            return LocalDate.of(Integer.parseInt(matcher.group(1)), 1, 1);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Ratio parseRatio(String value) {
        if (value == null) {
            return new Ratio(null, null);
        }
        Matcher matcher = RATIO_PATTERN.matcher(value);
        if (!matcher.find()) {
            return new Ratio(null, null);
        }
        Double rating = parseDouble(matcher.group(1).replace(',', '.'));
        Integer count = parseInt(matcher.group(2));
        // The site uses a 10-point scale; Grimmory displays provider ratings on
        // a 5-point scale, like the existing LubimyCzytac provider.
        return new Ratio(normalizeRating(rating), normalizeCount(count));
    }

    private Ratio parseDetailRating(Document doc) {
        String rating = text(doc.selectFirst("meta[itemprop=ratingValue]"));
        String count = text(doc.selectFirst("meta[itemprop=ratingCount]"));
        return new Ratio(normalizeRating(parseDouble(rating)), normalizeCount(parseInt(count)));
    }

    private String extractLabeledValue(String pageText, String label) {
        if (pageText == null) {
            return null;
        }
        Matcher matcher = Pattern.compile(Pattern.quote(label) + ":\\s*([^\\n]+)")
                .matcher(pageText);
        return matcher.find() ? trimToNull(matcher.group(1)) : null;
    }

    private String extractSeriesName(Document doc) {
        Element lastSeries = null;
        for (Element element : doc.select("a[href^=/series/id]")) {
            lastSeries = element;
        }
        return lastSeries != null ? trimToNull(lastSeries.text()) : null;
    }

    private String coverUrl(String id, String bookExt) {
        Integer numericId = parseInt(id);
        if (numericId == null) {
            return null;
        }
        String extension = bookExt != null && !bookExt.isBlank() ? bookExt : "jpg";
        return SITE_BASE + "/img/full/" + (numericId / 100) + "/" + numericId + "." + extension;
    }

    private String fullCoverUrl(String cover, String id) {
        if (cover != null) {
            String normalized = cover.replace("http://", "https://")
                    .replace("/img/preview/", "/img/full/");
            if (normalized.startsWith("//")) {
                normalized = "https:" + normalized;
            }
            return normalized;
        }
        return coverUrl(id, "jpg");
    }

    private String bookUrl(String id) {
        return SITE_BASE + "/lib/id" + id + "/";
    }

    private JsonNode getJson(String url) {
        String body = executeRequest(url, "application/json");
        if (body == null) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            int status = root.path("status").asInt(200);
            if (status >= 400) {
                log.error("Fantasy Worlds API returned status {} for {}", status, url);
                return null;
            }
            return root;
        } catch (RuntimeException e) {
            log.error("Failed to parse Fantasy Worlds JSON response from {}", url, e);
            return null;
        }
    }

    private String getHtml(String url) {
        return executeRequest(url, "text/html");
    }

    private synchronized String executeRequest(String url, String accept) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header("User-Agent", USER_AGENT)
                .header("Accept", accept)
                .header("Accept-Language", "ru-RU,ru;q=0.9")
                .header("Referer", SITE_BASE + "/")
                .GET()
                .build();

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                if (!waitForCrawlDelay()) {
                    return null;
                }
                HttpResponse<String> response;
                try {
                    response = httpClient.send(
                            request,
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                    );
                } finally {
                    markRequestFinished();
                }
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return response.body();
                }
                if (status == 404) {
                    log.debug("Fantasy Worlds resource not found: {}", url);
                    return null;
                }
                if (isRetryable(status) && attempt < MAX_RETRIES) {
                    long delay = retryDelayMillis(response, attempt);
                    log.warn("Fantasy Worlds request failed with status {} (attempt {}/{}), retrying in {} ms",
                            status, attempt, MAX_RETRIES, delay);
                    if (!sleep(delay)) {
                        return null;
                    }
                    continue;
                }

                log.error("Fantasy Worlds request failed with status {} for {}", status, url);
                return null;
            } catch (IOException e) {
                if (attempt < MAX_RETRIES) {
                    log.warn("Fantasy Worlds request failed (attempt {}/{}): {}",
                            attempt, MAX_RETRIES, e.getMessage());
                    if (!sleep(1000L * attempt)) {
                        return null;
                    }
                } else {
                    log.error("Failed to query Fantasy Worlds after {} attempts", MAX_RETRIES, e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Fantasy Worlds request interrupted");
                return null;
            }
        }
        return null;
    }

    private boolean waitForCrawlDelay() {
        if (crawlDelayMs <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        return now >= nextRequestAt || sleep(nextRequestAt - now);
    }

    /**
     * Starts the Crawl-delay window when the request completes, so the pause is
     * measured response-to-next-request rather than request-to-request.
     */
    private void markRequestFinished() {
        if (crawlDelayMs > 0) {
            nextRequestAt = System.currentTimeMillis() + crawlDelayMs;
        }
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

    private static String text(Element element) {
        if (element == null) {
            return null;
        }
        String value = "meta".equalsIgnoreCase(element.tagName()) && element.hasAttr("content")
                ? element.attr("content")
                : element.text();
        return trimToNull(value);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        return trimToNull(value.asText());
    }

    private static Float parseFloat(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Float.parseFloat(value.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDouble(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseInt(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
    }

    /**
     * Russian titles are heavily inflected ("Дюна" vs "Дюны"), so compare a
     * short prefix of every query word rather than requiring an exact substring.
     */
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
            String stem = token.substring(0, stemLength);
            if (!normalizedHaystack.contains(stem)) {
                return false;
            }
        }
        return true;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Double normalizeRating(Double rawRating) {
        return rawRating != null && rawRating > 0 ? rawRating / 2.0 : null;
    }

    private static Integer normalizeCount(Integer count) {
        return count != null && count > 0 ? count : null;
    }

    private record Ratio(Double rating, Integer count) {
    }
}
