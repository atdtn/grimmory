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
 * Metadata provider for labirint.ru.
 *
 * <p>Labirint has no public search API; search results and details are HTML.
 * Search cards carry title, authors, cover, publisher and series. The detail
 * page adds ISBN, page count, publication year, annotation and rating. The top
 * search results are hydrated from detail pages because those bibliographic
 * fields are the reason to use Labirint.</p>
 */
@Service
@Slf4j
public class LabirintParser implements BookParser, DetailedMetadataProvider {

    private static final String SITE_BASE = "https://www.labirint.ru";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final int REQUEST_TIMEOUT_SECONDS = 20;
    private static final int MAX_RETRIES = 3;
    private static final int MAX_RESULTS = 10;
    private static final int MAX_HYDRATED_RESULTS = 3;
    private static final long DEFAULT_REQUEST_INTERVAL_MS = 1_000L;
    private static final long MAX_RETRY_DELAY_MS = 30_000L;
    private static final Pattern NUMERIC_ID_PATTERN = Pattern.compile("\\d+");
    private static final Pattern ISBN_PATTERN = Pattern.compile(
            "(97[89][\\d-]{10,17}|[\\d-]{9,13}[0-9Xx])");
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(1[0-9]{3}|20[0-9]{2})\\b");
    private static final Pattern DIGITS_PATTERN = Pattern.compile("\\d+");

    private final AppSettingService appSettingService;
    private final HttpClient httpClient;
    private final long requestIntervalMs;
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private long nextRequestAt;

    @Autowired
    public LabirintParser(AppSettingService appSettingService, HttpClient httpClient) {
        this(appSettingService, httpClient, DEFAULT_REQUEST_INTERVAL_MS);
    }

    LabirintParser(AppSettingService appSettingService,
                   HttpClient httpClient,
                   long requestIntervalMs) {
        this.appSettingService = appSettingService;
        this.httpClient = httpClient;
        this.requestIntervalMs = Math.max(0L, requestIntervalMs);
    }

    private Optional<MetadataProviderSettings.Labirint> getSettings() {
        var appSettings = appSettingService.getAppSettings();
        if (appSettings == null || appSettings.getMetadataProviderSettings() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(appSettings.getMetadataProviderSettings().getLabirint());
    }

    @Override
    public boolean isEnabled() {
        return getSettings().map(MetadataProviderSettings.Labirint::isEnabled).orElse(false);
    }

    @Override
    public List<BookMetadata> fetchMetadata(Book book, FetchMetadataRequest request) {
        if (!isEnabled()) {
            log.debug("Labirint provider is disabled");
            return List.of();
        }

        String title = trimToNull(request.getTitle());
        String author = trimToNull(request.getAuthor());
        String isbn = trimToNull(request.getIsbn());
        if (title == null && author == null && isbn == null) {
            log.debug("Empty search query for Labirint");
            return List.of();
        }

        log.info("Fetching Labirint metadata for book: {}",
                book != null ? book.getTitle() : title);

        List<BookMetadata> results = List.of();
        for (String query : buildSearchQueries(title, author, isbn)) {
            List<BookMetadata> cards = search(query);
            if (cards.isEmpty()) {
                continue;
            }
            List<BookMetadata> candidates = cards.stream()
                    .filter(metadata -> matchesSearch(metadata, title, author, isbn))
                    .toList();
            if (candidates.isEmpty()) {
                continue;
            }
            List<BookMetadata> hydrated = hydrate(candidates,
                    isbn != null ? MAX_RESULTS : MAX_HYDRATED_RESULTS);
            if (isbn != null) {
                List<BookMetadata> exact = hydrated.stream()
                        .filter(metadata -> isbnMatches(metadata, isbn))
                        .toList();
                if (!exact.isEmpty()) {
                    results = exact;
                    break;
                }
                // ISBN is a hard filter: a fuzzy search result must not be
                // returned as the requested edition.
                continue;
            }
            results = hydrated;
            break;
        }

        if (results.isEmpty()) {
            log.info("No Labirint results found for query: {}",
                    title != null ? title : (author != null ? author : isbn));
            return List.of();
        }

        log.info("Found {} Labirint results for query: {}", results.size(),
                title != null ? title : (author != null ? author : isbn));
        return results;
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
        String html = getHtml(SITE_BASE + "/books/" + id + "/");
        if (html == null || html.isBlank()) {
            log.warn("Labirint details response was empty for book {}", id);
            return null;
        }

        try {
            return parseDetails(Jsoup.parse(html), id);
        } catch (RuntimeException e) {
            log.warn("Failed to parse Labirint details for book {}", id, e);
            return null;
        }
    }

    private List<String> buildSearchQueries(String title, String author, String isbn) {
        Set<String> queries = new LinkedHashSet<>();
        if (isbn != null) {
            queries.addAll(MetadataSearchUtils.isbnVariants(isbn));
        }
        if (title != null && author != null) {
            queries.add(title + " " + author);
        }
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
        String url = SITE_BASE + "/search/" + encoded + "/";
        log.debug("Searching Labirint: {}", url);

        String html = getHtml(url);
        if (html == null || html.isBlank()) {
            return List.of();
        }

        Document document = Jsoup.parse(html);
        List<BookMetadata> results = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        for (Element card : document.select("div.product-card[data-product-id]")) {
            if (!isBookCard(card)) {
                continue;
            }
            BookMetadata metadata = mapSearchCard(card);
            if (metadata != null && seenIds.add(metadata.getLabirintId())) {
                results.add(metadata);
                if (results.size() >= MAX_RESULTS) {
                    break;
                }
            }
        }
        return results;
    }

    private boolean isBookCard(Element card) {
        String dir = card.attr("data-dir");
        String sgenre = card.attr("data-sgenre-name");
        Element link = card.selectFirst("a.product-card__name[href]");
        String href = link != null ? link.attr("href") : "";
        return "books".equalsIgnoreCase(dir)
                || "книга".equalsIgnoreCase(sgenre)
                || href.startsWith("/books/");
    }

    private BookMetadata mapSearchCard(Element card) {
        String id = trimToNull(card.attr("data-product-id"));
        if (id == null) {
            return null;
        }
        String title = firstNonBlank(card.attr("data-name"),
                text(card.selectFirst("a.product-card__name")));
        if (title == null) {
            return null;
        }

        List<String> authors = new ArrayList<>();
        for (Element author : card.select(".product-card__author a")) {
            addIfNotBlank(authors, text(author));
        }

        Element cover = card.selectFirst("img.book-img-cover[data-src]");
        if (cover == null) {
            cover = card.selectFirst("img.book-img-cover");
        }
        String coverUrl = cover != null
                ? firstNonBlank(cover.attr("data-src"), cover.attr("src"))
                : null;

        Set<String> categories = new LinkedHashSet<>();
        addIfNotBlank(categories, card.attr("data-maingenre-name"));

        Integer reviewCount = firstInteger(text(card.selectFirst(".product-card__rating-reviews")));

        return BookMetadata.builder()
                .provider(MetadataProvider.LABIRINT)
                .labirintId(id)
                .title(title)
                .authors(authors)
                .thumbnailUrl(normalizeUrl(coverUrl))
                .externalUrl(SITE_BASE + "/books/" + id + "/")
                .publisher(blankToNull(card.attr("data-pubhouse")))
                .seriesName(blankToNull(card.attr("data-series")))
                .categories(categories)
                .labirintReviewCount(reviewCount != null && reviewCount > 0 ? reviewCount : null)
                .build();
    }

    private List<BookMetadata> hydrate(List<BookMetadata> cards, int hydrationLimit) {
        List<BookMetadata> results = new ArrayList<>(cards.size());
        for (int i = 0; i < cards.size(); i++) {
            BookMetadata card = cards.get(i);
            if (i < hydrationLimit && card.getLabirintId() != null) {
                BookMetadata details = fetchDetailedMetadata(card.getLabirintId());
                results.add(details != null ? merge(card, details) : card);
            } else {
                results.add(card);
            }
        }
        return results;
    }

    /**
     * Detail pages are authoritative for bibliographic fields, but should not
     * erase useful values that only the search card carried.
     */
    private BookMetadata merge(BookMetadata card, BookMetadata details) {
        Set<String> categories = details.getCategories() != null && !details.getCategories().isEmpty()
                ? details.getCategories() : card.getCategories();
        List<String> authors = details.getAuthors() != null && !details.getAuthors().isEmpty()
                ? details.getAuthors() : card.getAuthors();
        return details.toBuilder()
                .authors(authors)
                .description(firstNonBlank(details.getDescription(), card.getDescription()))
                .thumbnailUrl(firstNonBlank(details.getThumbnailUrl(), card.getThumbnailUrl()))
                .publisher(firstNonBlank(details.getPublisher(), card.getPublisher()))
                .seriesName(firstNonBlank(details.getSeriesName(), card.getSeriesName()))
                .pageCount(details.getPageCount() != null ? details.getPageCount() : card.getPageCount())
                .isbn13(firstNonBlank(details.getIsbn13(), card.getIsbn13()))
                .isbn10(firstNonBlank(details.getIsbn10(), card.getIsbn10()))
                .publishedDate(details.getPublishedDate() != null ? details.getPublishedDate() : card.getPublishedDate())
                .categories(categories)
                .language(firstNonBlank(details.getLanguage(), card.getLanguage()))
                .labirintRating(details.getLabirintRating() != null ? details.getLabirintRating() : card.getLabirintRating())
                .labirintReviewCount(details.getLabirintReviewCount() != null
                        ? details.getLabirintReviewCount() : card.getLabirintReviewCount())
                .build();
    }

    private BookMetadata parseDetails(Document document, String id) {
        List<String> authors = new ArrayList<>();
        Element authorLabel = findFeatureLabel(document, "Автор");
        if (authorLabel != null && authorLabel.parent() != null) {
            for (Element author : authorLabel.parent().select("a[href*=/authors/]")) {
                addIfNotBlank(authors, text(author));
            }
        }

        String title = extractTitle(document, authors);
        if (title == null) {
            return null;
        }

        Set<String> categories = new LinkedHashSet<>();
        for (Element genre : document.select("a[href*=/genres/] span[itemprop=name]")) {
            addIfNotBlank(categories, text(genre));
        }

        Element publisherLabel = findFeatureLabel(document, "Издательство");
        String publisher = null;
        LocalDate publishedDate = null;
        if (publisherLabel != null && publisherLabel.parent() != null) {
            Element publisherLink = publisherLabel.parent().selectFirst("a[href*=/pubhouse/]");
            publisher = text(publisherLink);
            Matcher year = YEAR_PATTERN.matcher(publisherLabel.parent().text());
            if (year.find()) {
                publishedDate = LocalDate.of(Integer.parseInt(year.group(1)), 1, 1);
            }
        }

        Element seriesLabel = findFeatureLabel(document, "Серия");
        String series = seriesLabel != null && seriesLabel.parent() != null
                ? text(seriesLabel.parent().selectFirst("a[href*=/series/]"))
                : null;

        Element pagesLabel = findFeatureLabel(document, "Страниц");
        Integer pageCount = pagesLabel != null && pagesLabel.parent() != null
                ? firstInteger(pagesLabel.parent().text())
                : null;

        String isbn = text(document.selectFirst("meta[itemprop=isbn]"));
        if (isbn == null) {
            isbn = extractIsbn(document);
        }

        String cover = text(document.selectFirst("meta[property=og:image]"));
        String ratingText = text(document.selectFirst("meta[itemprop=ratingValue]"));
        String reviewText = text(document.selectFirst("meta[itemprop=reviewCount]"));

        BookMetadata.BookMetadataBuilder builder = BookMetadata.builder()
                .provider(MetadataProvider.LABIRINT)
                .labirintId(id)
                .title(title)
                .authors(authors)
                .description(extractAnnotation(document))
                .publishedDate(publishedDate)
                .publisher(publisher)
                .seriesName(series)
                .pageCount(pageCount != null && pageCount > 0 ? pageCount : null)
                .thumbnailUrl(normalizeUrl(cover))
                .externalUrl(canonicalUrl(document, id))
                .categories(categories)
                .labirintRating(parseDouble(ratingText))
                .labirintReviewCount(parseInteger(reviewText));

        applyIsbn(builder, isbn);
        return builder.build();
    }

    private String extractTitle(Document document, List<String> authors) {
        String h1 = text(document.selectFirst("h1[itemprop=name]"));
        if (h1 != null) {
            String cleaned = stripAuthorSuffix(h1, authors);
            if (cleaned != null) {
                return cleaned;
            }
        }

        String ogTitle = text(document.selectFirst("meta[property=og:title]"));
        if (ogTitle != null) {
            String value = ogTitle.startsWith("Книга:")
                    ? ogTitle.substring("Книга:".length()).trim()
                    : ogTitle;
            int separator = value.lastIndexOf(" - ");
            if (separator > 0) {
                value = value.substring(0, separator).trim();
            }
            int cut = value.indexOf(". Купить");
            if (cut > 0) {
                value = value.substring(0, cut).trim();
            }
            return trimToNull(value);
        }
        return null;
    }

    private String stripAuthorSuffix(String h1, List<String> authors) {
        String title = h1.trim();
        int separator = title.lastIndexOf(": ");
        if (separator <= 0) {
            return title;
        }
        String suffix = normalizeText(title.substring(separator + 2));
        if (suffix.isBlank()) {
            return title;
        }
        for (String author : authors) {
            String normalizedAuthor = normalizeText(author);
            boolean allTokensPresent = true;
            for (String token : suffix.split(" ")) {
                if (!token.isBlank() && !normalizedAuthor.contains(token)) {
                    allTokensPresent = false;
                    break;
                }
            }
            if (allTokensPresent) {
                return trimToNull(title.substring(0, separator));
            }
        }
        return title;
    }

    private String extractAnnotation(Document document) {
        Element annotation = document.selectFirst("#annotation");
        if (annotation == null) {
            return null;
        }

        Element largest = null;
        int largestLength = 0;
        for (Element block : annotation.select("div")) {
            String text = block.text().trim();
            if (text.length() > largestLength) {
                largestLength = text.length();
                largest = block;
            }
        }
        String text = largest != null ? largest.text() : annotation.text();
        text = text.replaceFirst("^Аннотация\\s*", "")
                .replaceFirst("\\s*(Свернуть|Развернуть)\\s*$", "")
                .trim();
        return text.isEmpty() ? null : text;
    }

    private String extractIsbn(Document document) {
        Element label = findFeatureLabel(document, "ISBN");
        if (label == null || label.parent() == null) {
            return null;
        }
        Matcher matcher = ISBN_PATTERN.matcher(label.parent().text());
        return matcher.find() ? matcher.group(1) : null;
    }

    private Element findFeatureLabel(Document document, String label) {
        for (Element element : document.select("div")) {
            String ownText = element.ownText().replace(":", "").trim();
            if (label.equalsIgnoreCase(ownText)) {
                return element;
            }
        }
        return null;
    }

    private void applyIsbn(BookMetadata.BookMetadataBuilder builder, String rawIsbn) {
        if (rawIsbn == null) {
            return;
        }
        Matcher matcher = ISBN_PATTERN.matcher(rawIsbn);
        String isbn = matcher.find() ? matcher.group(1) : null;
        if (isbn == null) {
            return;
        }
        String cleaned = isbn.replaceAll("[^0-9Xx]", "");
        if (cleaned.endsWith("x")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1) + "X";
        }
        if (cleaned.length() == 13) {
            builder.isbn13(cleaned);
        } else if (cleaned.length() == 10) {
            builder.isbn10(cleaned);
        }
    }

    private boolean matchesSearch(BookMetadata metadata, String title, String author, String isbn) {
        if (isbn != null) {
            // Cards do not expose ISBN; the exact ISBN check happens after
            // hydration. Keep title/author candidates here.
            if (title != null) {
                return matchesText(metadata.getTitle(), title);
            }
            if (author != null) {
                return authorMatches(metadata, author);
            }
            return true;
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

    private boolean isbnMatches(BookMetadata metadata, String isbn) {
        String normalized = normalizeIsbn(isbn);
        if (normalized == null) {
            return false;
        }
        String requested13 = toIsbn13(normalized);
        String metadata13 = toIsbn13(normalizeIsbn(metadata.getIsbn13()));
        String metadata10 = normalizeIsbn(metadata.getIsbn10());
        return normalized.equals(metadata.getIsbn10())
                || normalized.equals(metadata.getIsbn13())
                || (requested13 != null && requested13.equals(metadata13))
                || (metadata10 != null && requested13 != null
                && requested13.equals(toIsbn13(metadata10)));
    }

    private String normalizeIsbn(String isbn) {
        if (isbn == null) {
            return null;
        }
        String cleaned = isbn.replaceAll("[^0-9Xx]", "");
        if (cleaned.endsWith("x")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1) + "X";
        }
        return cleaned.isEmpty() ? null : cleaned;
    }

    private String toIsbn13(String isbn) {
        if (isbn == null) {
            return null;
        }
        if (isbn.length() == 13) {
            return isbn;
        }
        if (isbn.length() != 10) {
            return null;
        }
        String core = "978" + isbn.substring(0, 9);
        int sum = 0;
        for (int i = 0; i < core.length(); i++) {
            int digit = core.charAt(i) - '0';
            sum += (i % 2 == 0) ? digit : digit * 3;
        }
        int check = (10 - (sum % 10)) % 10;
        return core + check;
    }

    private String canonicalUrl(Document document, String id) {
        Element canonical = document.selectFirst("link[rel=canonical]");
        String href = canonical != null ? trimToNull(canonical.attr("href")) : null;
        return href != null ? href : SITE_BASE + "/books/" + id + "/";
    }

    private String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        if (url.startsWith("/")) {
            return SITE_BASE + url;
        }
        return url;
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
                    log.debug("Labirint resource not found: {}", url);
                    return null;
                }
                if (isRetryable(status) && attempt < MAX_RETRIES) {
                    long delay = retryDelayMillis(response, attempt);
                    log.warn("Labirint request failed with status {} (attempt {}/{}), retrying in {} ms",
                            status, attempt, MAX_RETRIES, delay);
                    if (!sleep(delay)) {
                        return null;
                    }
                    continue;
                }
                log.error("Labirint request failed with status {} for {}", status, url);
                return null;
            } catch (IOException e) {
                if (attempt < MAX_RETRIES) {
                    log.warn("Labirint request failed (attempt {}/{}): {}", attempt, MAX_RETRIES, e.getMessage());
                    if (!sleep(1000L * attempt)) {
                        return null;
                    }
                } else {
                    log.error("Failed to query Labirint after {} attempts", MAX_RETRIES, e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Labirint request interrupted");
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

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void addIfNotBlank(List<String> values, String value) {
        if (value != null && !value.isBlank() && !values.contains(value)) {
            values.add(value);
        }
    }

    private static void addIfNotBlank(Set<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value);
        }
    }

    private static Integer firstInteger(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = DIGITS_PATTERN.matcher(value.replaceAll("[\\s\\u00a0]", ""));
        return matcher.find() ? parseInteger(matcher.group()) : null;
    }

    private static Integer parseInteger(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
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

    private static String normalizeText(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
