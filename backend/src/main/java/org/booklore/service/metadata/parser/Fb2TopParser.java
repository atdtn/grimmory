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

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Metadata provider for fb2.top.
 *
 * <p>The site has no public API: search results and details are Laravel Blade
 * HTML. Search is requested with the {@code text} parameter and returns at most
 * 40 cards without pagination. The detail page exposes schema.org microdata.
 * The catalogue has no ISBN, so ISBN-only requests return an empty list.</p>
 */
@Service
@Slf4j
public class Fb2TopParser implements BookParser, DetailedMetadataProvider {

    private static final String SITE_BASE = "https://fb2.top";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final int REQUEST_TIMEOUT_SECONDS = 20;
    private static final int MAX_RETRIES = 3;
    private static final int MAX_RESULTS = 10;
    private static final int MAX_HYDRATED_RESULTS = 3;
    private static final long DEFAULT_REQUEST_INTERVAL_MS = 1_000L;
    private static final long MAX_RETRY_DELAY_MS = 30_000L;
    private static final Pattern ITEM_ID_PATTERN =
            Pattern.compile("[a-z0-9][a-z0-9_-]*-\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(1[0-9]{3}|20[0-9]{2})\\b");
    private static final Pattern DIGITS_PATTERN = Pattern.compile("\\d+");
    private static final Pattern DECIMAL_PATTERN = Pattern.compile("\\d+(?:[.,]\\d+)?");
    private static final String PLACEHOLDER_COVER = "noposter";

    private final AppSettingService appSettingService;
    private final HttpClient httpClient;
    private final long requestIntervalMs;
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private long nextRequestAt;

    @Autowired
    public Fb2TopParser(AppSettingService appSettingService, HttpClient httpClient) {
        this(appSettingService, httpClient, DEFAULT_REQUEST_INTERVAL_MS);
    }

    Fb2TopParser(AppSettingService appSettingService,
                 HttpClient httpClient,
                 long requestIntervalMs) {
        this.appSettingService = appSettingService;
        this.httpClient = httpClient;
        this.requestIntervalMs = Math.max(0L, requestIntervalMs);
    }

    private Optional<MetadataProviderSettings.Fb2Top> getSettings() {
        var appSettings = appSettingService.getAppSettings();
        if (appSettings == null || appSettings.getMetadataProviderSettings() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(appSettings.getMetadataProviderSettings().getFb2Top());
    }

    @Override
    public boolean isEnabled() {
        return getSettings().map(MetadataProviderSettings.Fb2Top::isEnabled).orElse(false);
    }

    @Override
    public List<BookMetadata> fetchMetadata(Book book, FetchMetadataRequest request) {
        if (!isEnabled()) {
            log.debug("FB2.top provider is disabled");
            return List.of();
        }

        String title = trimToNull(request.getTitle());
        String author = trimToNull(request.getAuthor());
        String isbn = trimToNull(request.getIsbn());
        if (title == null && author == null) {
            // FB2.top has no ISBN catalogue, so an ISBN-only request cannot be
            // resolved and must not produce fuzzy title matches.
            if (isbn != null) {
                log.debug("FB2.top does not support ISBN-only search");
            } else {
                log.debug("Empty search query for FB2.top");
            }
            return List.of();
        }

        log.info("Fetching FB2.top metadata for book: {}",
                book != null ? book.getTitle() : title);

        for (String query : buildSearchQueries(title, author)) {
            List<BookMetadata> candidates = search(query).stream()
                    .filter(metadata -> matchesSearch(metadata, title, author))
                    .limit(MAX_RESULTS)
                    .toList();
            if (candidates.isEmpty()) {
                continue;
            }

            List<BookMetadata> hydrated = hydrate(candidates);
            log.info("Found {} FB2.top results for query: {}", hydrated.size(), query);
            return hydrated;
        }

        log.info("No FB2.top results found for query: {}",
                title != null ? title : author);
        return List.of();
    }

    @Override
    public BookMetadata fetchTopMetadata(Book book, FetchMetadataRequest request) {
        List<BookMetadata> results = fetchMetadata(book, request);
        return results.isEmpty() ? null : results.getFirst();
    }

    @Override
    public BookMetadata fetchDetailedMetadata(String providerItemId) {
        String id = trimToNull(providerItemId);
        if (!isEnabled() || id == null || !ITEM_ID_PATTERN.matcher(id).matches()) {
            return null;
        }

        String url = SITE_BASE + "/" + id;
        String html = getHtml(url);
        if (html == null || html.isBlank()) {
            log.warn("FB2.top details response was empty for {}", url);
            return null;
        }

        try {
            BookMetadata metadata = parseDetails(Jsoup.parse(html), id);
            if (metadata == null) {
                log.warn("FB2.top details page contained no recognizable title for {}", url);
            }
            return metadata;
        } catch (RuntimeException e) {
            log.warn("Failed to parse FB2.top details for id {}", id, e);
            return null;
        }
    }

    private List<String> buildSearchQueries(String title, String author) {
        Set<String> queries = new LinkedHashSet<>();
        if (title != null) {
            queries.add(title);
        }
        if (author != null) {
            queries.addAll(MetadataSearchUtils.authorVariants(author));
        }
        return new ArrayList<>(queries);
    }

    private List<BookMetadata> search(String query) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
                .replace("+", "%20");
        String url = SITE_BASE + "/search?text=" + encoded;
        log.debug("Searching FB2.top: {}", url);

        String html = getHtml(url);
        if (html == null || html.isBlank()) {
            return List.of();
        }

        Document document = Jsoup.parse(html);
        List<BookMetadata> results = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        for (Element card : document.select("div.book-card")) {
            BookMetadata metadata = mapSearchCard(card);
            if (metadata != null && seenIds.add(metadata.getFb2TopId())) {
                results.add(metadata);
            }
        }
        return results;
    }

    private BookMetadata mapSearchCard(Element card) {
        Element link = card.selectFirst(".card-title a[href]");
        if (link == null) {
            return null;
        }
        String id = itemId(link.attr("href"));
        String title = trimToNull(link.text());
        if (id == null || title == null) {
            return null;
        }

        List<String> authors = new ArrayList<>();
        Element authorsBox = card.selectFirst(".authors-box");
        if (authorsBox != null) {
            for (String author : authorsBox.text().split(",")) {
                addIfNotBlank(authors, author);
            }
        }

        Element cover = card.selectFirst(".poster-box img");
        String coverUrl = cover != null
                ? firstNonPlaceholderCover(cover.hasAttr("data-src") ? cover.attr("data-src") : null,
                cover.attr("src"))
                : null;

        Double rating = parseDouble(text(card.selectFirst(".rating-box")));

        return BookMetadata.builder()
                .provider(MetadataProvider.FB2_TOP)
                .fb2TopId(id)
                .title(title)
                .authors(authors)
                .thumbnailUrl(normalizeCoverUrl(coverUrl))
                .externalUrl(SITE_BASE + "/" + id)
                .fb2TopRating(rating != null && rating > 0 ? rating : null)
                .build();
    }

    private List<BookMetadata> hydrate(List<BookMetadata> cards) {
        List<BookMetadata> results = new ArrayList<>(cards.size());
        for (int i = 0; i < cards.size(); i++) {
            BookMetadata card = cards.get(i);
            if (i < MAX_HYDRATED_RESULTS && card.getFb2TopId() != null) {
                BookMetadata details = fetchDetailedMetadata(card.getFb2TopId());
                results.add(details != null ? merge(card, details) : card);
            } else {
                results.add(card);
            }
        }
        return results;
    }

    /**
     * Detail pages are authoritative for bibliographic fields, but should not
     * erase values that only the search card carried.
     */
    private BookMetadata merge(BookMetadata card, BookMetadata details) {
        List<String> authors = details.getAuthors() != null && !details.getAuthors().isEmpty()
                ? details.getAuthors() : card.getAuthors();
        Set<String> categories = details.getCategories() != null && !details.getCategories().isEmpty()
                ? details.getCategories() : card.getCategories();
        return details.toBuilder()
                .authors(authors)
                .categories(categories)
                .thumbnailUrl(firstNonBlank(details.getThumbnailUrl(), card.getThumbnailUrl()))
                .externalUrl(firstNonBlank(details.getExternalUrl(), card.getExternalUrl()))
                .description(firstNonBlank(details.getDescription(), card.getDescription()))
                .publishedDate(details.getPublishedDate() != null
                        ? details.getPublishedDate() : card.getPublishedDate())
                .seriesName(firstNonBlank(details.getSeriesName(), card.getSeriesName()))
                .pageCount(details.getPageCount() != null ? details.getPageCount() : card.getPageCount())
                .language(firstNonBlank(details.getLanguage(), card.getLanguage()))
                .fb2TopRating(details.getFb2TopRating() != null
                        ? details.getFb2TopRating() : card.getFb2TopRating())
                .fb2TopReviewCount(details.getFb2TopReviewCount() != null
                        ? details.getFb2TopReviewCount() : card.getFb2TopReviewCount())
                .build();
    }

    private BookMetadata parseDetails(Document document, String id) {
        String title = text(document.selectFirst("h1[itemprop=name]"));
        if (title == null) {
            title = titleFromOpenGraph(document);
        }
        if (title == null) {
            return null;
        }

        List<String> authors = new ArrayList<>();
        for (Element author : document.select("[itemprop=author]")) {
            addIfNotBlank(authors, text(author));
        }

        Set<String> categories = new LinkedHashSet<>();
        for (Element genre : document.select("[itemprop=genre]")) {
            addIfNotBlank(categories, text(genre));
        }

        String cover = text(document.selectFirst("img.book-info-poster-img"));
        if (cover == null) {
            cover = text(document.selectFirst("[itemprop=image]"));
        }
        if (cover == null) {
            cover = text(document.selectFirst("meta[property=og:image]"));
        }

        BookMetadata.BookMetadataBuilder builder = BookMetadata.builder()
                .provider(MetadataProvider.FB2_TOP)
                .fb2TopId(id)
                .title(title)
                .authors(authors)
                .description(extractAnnotation(document))
                .publishedDate(parseYear(text(document.selectFirst("[itemprop=copyrightYear]"))))
                .seriesName(extractSeries(document))
                .pageCount(positiveInteger(text(document.selectFirst("[itemprop=numberOfPages]"))))
                .thumbnailUrl(normalizeCoverUrl(cover))
                .externalUrl(canonicalUrl(document, id))
                .categories(categories)
                .language(extractLanguage(document))
                .fb2TopRating(extractRating(document))
                .fb2TopReviewCount(extractReviewCount(document));

        return builder.build();
    }

    private String titleFromOpenGraph(Document document) {
        String value = text(document.selectFirst("meta[property=og:title]"));
        if (value == null) {
            return null;
        }
        int separator = value.indexOf(" - ");
        if (separator > 0) {
            value = value.substring(0, separator);
        }
        return trimToNull(value);
    }

    private String extractAnnotation(Document document) {
        Element annotation = document.selectFirst("[itemprop=description] .annotation");
        if (annotation == null) {
            annotation = document.selectFirst(".annotation");
        }
        if (annotation == null) {
            return null;
        }
        String text = annotation.text().replace('\u00a0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        return text.isEmpty() ? null : text;
    }

    private String extractSeries(Document document) {
        for (Element row : document.select(".info-row")) {
            if (!row.ownText().contains("Входит в сери")) {
                continue;
            }
            Element series = row.selectFirst("a[href^=/series/]");
            String name = text(series);
            if (name != null) {
                return name;
            }
        }
        return null;
    }

    private String extractLanguage(Document document) {
        Element html = document.selectFirst("html[lang]");
        String lang = html != null ? html.attr("lang") : null;
        if (lang == null) {
            return "ru";
        }
        return LanguageNormalizer.normalize(lang);
    }

    private Double extractRating(Document document) {
        Element ratingValue = document.selectFirst("[itemprop=aggregateRating] [itemprop=ratingValue]");
        if (ratingValue == null) {
            ratingValue = document.selectFirst("[itemprop=ratingValue]");
        }
        Double rating = parseDouble(text(ratingValue));
        return rating != null && rating > 0 ? rating : null;
    }

    private Integer extractReviewCount(Document document) {
        Element count = document.selectFirst("[itemprop=aggregateRating] [itemprop=ratingCount]");
        if (count == null) {
            count = document.selectFirst("[itemprop=ratingCount]");
        }
        Integer value = positiveInteger(text(count));
        return value;
    }

    private boolean matchesSearch(BookMetadata metadata, String title, String author) {
        if (title != null && !MetadataSearchUtils.normalizeText(metadata.getTitle())
                .equals(MetadataSearchUtils.normalizeText(title))) {
            return false;
        }
        if (author != null) {
            return authorMatches(metadata, author);
        }
        return title != null;
    }

    private boolean authorMatches(BookMetadata metadata, String author) {
        if (metadata.getAuthors() == null || metadata.getAuthors().isEmpty()) {
            return false;
        }
        for (String variant : MetadataSearchUtils.authorVariants(author)) {
            if (metadata.getAuthors().stream().anyMatch(name -> MetadataSearchUtils.matchesText(name, variant))) {
                return true;
            }
        }
        return false;
    }

    private String itemId(String href) {
        if (href == null || href.isBlank()) {
            return null;
        }
        String path = href.trim();
        if (path.startsWith(SITE_BASE)) {
            path = path.substring(SITE_BASE.length());
        }
        if (!path.startsWith("/")) {
            return null;
        }
        path = path.substring(1);
        int queryOrFragment = path.indexOf('?');
        if (queryOrFragment < 0) {
            queryOrFragment = path.indexOf('#');
        }
        if (queryOrFragment >= 0) {
            path = path.substring(0, queryOrFragment);
        }
        return ITEM_ID_PATTERN.matcher(path).matches() ? path : null;
    }

    private String canonicalUrl(Document document, String id) {
        String canonical = text(document.selectFirst("link[rel=canonical]"));
        if (canonical != null) {
            return canonical;
        }
        String openGraph = text(document.selectFirst("meta[property=og:url]"));
        return openGraph != null ? openGraph : SITE_BASE + "/" + id;
    }

    private String firstNonPlaceholderCover(String... values) {
        for (String value : values) {
            String candidate = trimToNull(value);
            if (candidate != null && !isPlaceholderCover(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isPlaceholderCover(String url) {
        return url != null && url.toLowerCase(java.util.Locale.ROOT).contains(PLACEHOLDER_COVER);
    }

    private String normalizeCoverUrl(String url) {
        String candidate = trimToNull(url);
        if (candidate == null || isPlaceholderCover(candidate)) {
            return null;
        }
        if (candidate.startsWith("//")) {
            return "https:" + candidate;
        }
        if (candidate.startsWith("/")) {
            return SITE_BASE + candidate;
        }
        return candidate;
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
                if (status >= 200 && status < 300) {
                    return response.body();
                }
                if (status == 404) {
                    log.debug("FB2.top resource not found: {}", url);
                    return null;
                }
                if (isRetryable(status) && attempt < MAX_RETRIES) {
                    long delay = retryDelayMillis(response, attempt);
                    log.warn("FB2.top request failed with status {} (attempt {}/{}), retrying in {} ms",
                            status, attempt, MAX_RETRIES, delay);
                    if (!sleep(delay)) {
                        return null;
                    }
                    continue;
                }
                log.error("FB2.top request failed with status {} for {}", status, url);
                return null;
            } catch (IOException e) {
                if (attempt < MAX_RETRIES) {
                    log.warn("FB2.top request failed (attempt {}/{}): {}", attempt, MAX_RETRIES, e.getMessage());
                    if (!sleep(1000L * attempt)) {
                        return null;
                    }
                } else {
                    log.error("Failed to query FB2.top after {} attempts", MAX_RETRIES, e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("FB2.top request interrupted");
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

    private static String text(Element element) {
        if (element == null) {
            return null;
        }
        String value = "meta".equalsIgnoreCase(element.tagName()) && element.hasAttr("content")
                ? element.attr("content")
                : element.text();
        return trimToNull(value);
    }

    private static LocalDate parseYear(String value) {
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

    private static Integer positiveInteger(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = DIGITS_PATTERN.matcher(value.replaceAll("[\\s\\u00a0]", ""));
        if (!matcher.find()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(matcher.group());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDouble(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = DECIMAL_PATTERN.matcher(value.replace('\u00a0', ' '));
        if (!matcher.find()) {
            return null;
        }
        try {
            return Double.parseDouble(matcher.group().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static void addIfNotBlank(List<String> values, String value) {
        if (value != null && !value.isBlank() && !values.contains(value.trim())) {
            values.add(value.trim());
        }
    }

    private static void addIfNotBlank(Set<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value.trim());
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
