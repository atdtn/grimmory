package org.booklore.service.metadata.parser;

import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.MetadataProviderSettings;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.service.appsettings.AppSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Fb2TopParserTest {

    @Mock
    private AppSettingService appSettingService;

    @Mock
    private HttpClient httpClient;

    private Fb2TopParser parser;
    private String searchMasterHtml;
    private String detailMasterHtml;
    private String detailNoposterHtml;

    @BeforeEach
    void setUp() throws IOException {
        parser = new Fb2TopParser(appSettingService, httpClient, 0L);
        searchMasterHtml = readFixture("search-master.html");
        detailMasterHtml = readFixture("detail-master.html");
        detailNoposterHtml = readFixture("detail-noposter.html");
    }

    @Test
    void fetchMetadata_returnsEmptyWhenDisabled() {
        mockSettings(false);

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("Мастер и Маргарита").build(),
                FetchMetadataRequest.builder().title("Мастер и Маргарита").build());

        assertThat(results).isEmpty();
        verifyNoInteractions(httpClient);
    }

    @Test
    void fetchMetadata_returnsEmptyForEmptyQuery() {
        mockSettings(true);

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("Мастер и Маргарита").build(),
                FetchMetadataRequest.builder().build());

        assertThat(results).isEmpty();
        verifyNoInteractions(httpClient);
    }

    @Test
    void fetchMetadata_returnsEmptyForIsbnOnlyWithoutHttp() {
        mockSettings(true);

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("Мастер и Маргарита").build(),
                FetchMetadataRequest.builder().isbn("978-5-00112-063-6").build());

        assertThat(results).isEmpty();
        verifyNoInteractions(httpClient);
    }

    @Test
    void fetchMetadata_parsesCardsFiltersNoiseAndHydratesTopResults() throws Exception {
        mockSettings(true);
        mockHttp(Map.of(
                "https://fb2.top/search", searchMasterHtml,
                "https://fb2.top/master-i-margarita-66372", detailMasterHtml));

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("Мастер и Маргарита").build(),
                FetchMetadataRequest.builder()
                        .title("Мастер и Маргарита")
                        .author("Михаил Булгаков")
                        .build());

        assertThat(results).hasSize(3);
        assertThat(results).extracting(BookMetadata::getTitle)
                .containsOnly("Мастер и Маргарита");
        assertThat(results).extracting(BookMetadata::getFb2TopId)
                .doesNotContain("analiz-romana-m-bulgakova-quot-master-i-margarita-quot-279514",
                        "tom-9-master-i-margarita-745446",
                        "master-i-margarita-okonchanie-520303",
                        "master-i-margarita-999999");

        BookMetadata first = results.getFirst();
        assertThat(first.getProvider()).isEqualTo(MetadataProvider.FB2_TOP);
        assertThat(first.getFb2TopId()).isEqualTo("master-i-margarita-66372");
        assertThat(first.getTitle()).isEqualTo("Мастер и Маргарита");
        assertThat(first.getAuthors()).containsExactly("Михаил Афанасьевич Булгаков");
        assertThat(first.getPublishedDate()).isEqualTo(LocalDate.of(2006, 1, 1));
        assertThat(first.getPageCount()).isEqualTo(386);
        assertThat(first.getFb2TopRating()).isEqualTo(3.66216);
        assertThat(first.getFb2TopReviewCount()).isEqualTo(37);
        assertThat(first.getDescription()).contains("Роман о добре и зле");
        assertThat(first.getCategories()).contains("Советская классическая проза");
        assertThat(first.getSeriesName()).isEqualTo("Эксмо-Классика");
        assertThat(first.getThumbnailUrl()).isEqualTo(
                "https://sun9-39.userapi.com/impg/UybK8JGw6-Cvb1WMHVxgVNOFJflt2AwfyHWbnQ/ycoSEwPrLNY.jpg?size=320x419&quality=96&type=album");
        assertThat(first.getExternalUrl()).isEqualTo("https://fb2.top/master-i-margarita-66372");
        assertThat(first.getIsbn13()).isNull();
        assertThat(first.getIsbn10()).isNull();

        BookMetadata fallbackToCard = results.get(1);
        assertThat(fallbackToCard.getFb2TopId()).isEqualTo("master-i-margarita-588297");
        assertThat(fallbackToCard.getAuthors()).containsExactly("Михаил Булгаков");
        assertThat(fallbackToCard.getFb2TopRating()).isEqualTo(4.15);
        assertThat(fallbackToCard.getThumbnailUrl()).startsWith("https://sun9-32.userapi.com/");

        BookMetadata placeholderCover = results.get(2);
        assertThat(placeholderCover.getFb2TopId()).isEqualTo("master-i-margarita-262795");
        assertThat(placeholderCover.getThumbnailUrl()).isNull();
    }

    @Test
    void fetchMetadata_usesTextQueryParameter() throws Exception {
        mockSettings(true);
        List<HttpRequest> requests = new CopyOnWriteArrayList<>();
        when(httpClient.<String>send(any(HttpRequest.class), any()))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    requests.add(request);
                    if (request.uri().getPath().equals("/search")) {
                        return response(200, searchMasterHtml, Map.of());
                    }
                    return response(404, "", Map.of());
                });

        parser.fetchMetadata(
                Book.builder().title("Мастер и Маргарита").build(),
                FetchMetadataRequest.builder().title("Мастер и Маргарита").build());

        HttpRequest searchRequest = requests.stream()
                .filter(request -> request.uri().getPath().equals("/search"))
                .findFirst()
                .orElseThrow();
        String query = searchRequest.uri().getRawQuery();
        assertThat(query).startsWith("text=");
        assertThat(query).doesNotContain("q=");
        assertThat(query).contains("%D0%9C%D0%B0%D1%81%D1%82%D0%B5%D1%80");
    }

    @Test
    void fetchMetadata_supportsAuthorOnlySearch() throws Exception {
        mockSettings(true);
        mockHttp(Map.of(
                "https://fb2.top/search", searchMasterHtml,
                "https://fb2.top/master-i-margarita-66372", detailMasterHtml));

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("").build(),
                FetchMetadataRequest.builder().author("Булгаков").build());

        assertThat(results).isNotEmpty();
        assertThat(results).allSatisfy(metadata -> assertThat(metadata.getAuthors())
                .anyMatch(author -> author.contains("Булгаков")));
    }

    @Test
    void fetchDetailedMetadata_parsesMicrodata() throws Exception {
        mockSettings(true);
        mockHttp(Map.of("https://fb2.top/master-i-margarita-66372", detailMasterHtml));

        BookMetadata result = parser.fetchDetailedMetadata("master-i-margarita-66372");

        assertThat(result).isNotNull();
        assertThat(result.getProvider()).isEqualTo(MetadataProvider.FB2_TOP);
        assertThat(result.getFb2TopId()).isEqualTo("master-i-margarita-66372");
        assertThat(result.getTitle()).isEqualTo("Мастер и Маргарита");
        assertThat(result.getAuthors()).containsExactly("Михаил Афанасьевич Булгаков");
        assertThat(result.getPublishedDate()).isEqualTo(LocalDate.of(2006, 1, 1));
        assertThat(result.getPageCount()).isEqualTo(386);
        assertThat(result.getFb2TopRating()).isEqualTo(3.66216);
        assertThat(result.getFb2TopReviewCount()).isEqualTo(37);
        assertThat(result.getCategories()).containsExactly(
                "Советская классическая проза", "Русская классическая проза");
        assertThat(result.getSeriesName()).isEqualTo("Эксмо-Классика");
        assertThat(result.getDescription()).contains("Роман о добре и зле",
                "Одно из главных произведений");
        assertThat(result.getExternalUrl()).isEqualTo("https://fb2.top/master-i-margarita-66372");
        assertThat(result.getLanguage()).isEqualTo("ru");
        assertThat(result.getThumbnailUrl()).contains("sun9-39.userapi.com");
        assertThat(result.getIsbn13()).isNull();
        assertThat(result.getIsbn10()).isNull();
    }

    @Test
    void fetchDetailedMetadata_readsCoverFromImageAttributeWithoutOpenGraph() throws Exception {
        mockSettings(true);
        String html = """
                <!doctype html><html lang="ru"><head>
                  <link rel="canonical" href="https://fb2.top/master-i-margarita-66372"/>
                </head><body>
                  <img src="https://sun9-1.userapi.com/cover.jpg" class="book-info-poster-img" itemprop="image">
                  <h1 itemprop="name">Мастер и Маргарита</h1>
                </body></html>
                """;
        mockHttp(Map.of("https://fb2.top/master-i-margarita-66372", html));

        BookMetadata result = parser.fetchDetailedMetadata("master-i-margarita-66372");

        assertThat(result).isNotNull();
        assertThat(result.getThumbnailUrl()).isEqualTo("https://sun9-1.userapi.com/cover.jpg");
    }

    @Test
    void retryDelayHonoursHttpDateRetryAfter() {
        String retryAfter = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(2));

        assertThat(Fb2TopParser.retryDelayMillis(retryAfter, 1))
                .isBetween(1_000L, 2_500L);
        assertThat(Fb2TopParser.retryDelayMillis("not-a-date", 2)).isEqualTo(2_000L);
    }

    @Test
    void fetchDetailedMetadata_ignoresPlaceholderCover() throws Exception {
        mockSettings(true);
        mockHttp(Map.of("https://fb2.top/master-i-margarita-66372", detailNoposterHtml));

        BookMetadata result = parser.fetchDetailedMetadata("master-i-margarita-66372");

        assertThat(result).isNotNull();
        assertThat(result.getTitle()).isEqualTo("Мастер и Маргарита");
        assertThat(result.getThumbnailUrl()).isNull();
    }

    @Test
    void fetchDetailedMetadata_retriesOnTooManyRequests() throws Exception {
        mockSettings(true);
        HttpResponse<String> tooManyRequests = response(429, "", Map.of("Retry-After", List.of("0")));
        HttpResponse<String> ok = response(200, detailMasterHtml, Map.of());
        when(httpClient.<String>send(any(HttpRequest.class), any()))
                .thenReturn(tooManyRequests, ok);

        BookMetadata result = parser.fetchDetailedMetadata("master-i-margarita-66372");

        assertThat(result).isNotNull();
        verify(httpClient, times(2)).send(any(HttpRequest.class), any());
    }

    @Test
    void fetchDetailedMetadata_returnsNullForNonRetryableStatus() throws Exception {
        mockSettings(true);
        HttpResponse<String> badRequest = response(400, "", Map.of());
        when(httpClient.<String>send(any(HttpRequest.class), any()))
                .thenReturn(badRequest);

        BookMetadata result = parser.fetchDetailedMetadata("master-i-margarita-66372");

        assertThat(result).isNull();
        verify(httpClient, times(1)).send(any(HttpRequest.class), any());
    }

    @Test
    void fetchDetailedMetadata_rejectsInvalidIdWithoutHttp() {
        mockSettings(true);

        assertThat(parser.fetchDetailedMetadata("../etc/passwd")).isNull();
        verifyNoInteractions(httpClient);
    }

    @Test
    void metadataProviderSerializesAsFrontendId() {
        assertThat(new tools.jackson.databind.ObjectMapper()
                .writeValueAsString(MetadataProvider.FB2_TOP))
                .isEqualTo("\"Fb2Top\"");
    }

    private void mockSettings(boolean enabled) {
        AppSettings appSettings = new AppSettings();
        MetadataProviderSettings providerSettings = new MetadataProviderSettings();
        MetadataProviderSettings.Fb2Top fb2Top = new MetadataProviderSettings.Fb2Top();
        fb2Top.setEnabled(enabled);
        providerSettings.setFb2Top(fb2Top);
        appSettings.setMetadataProviderSettings(providerSettings);
        when(appSettingService.getAppSettings()).thenReturn(appSettings);
    }

    private void mockHttp(Map<String, String> bodiesByUrlPrefix) throws Exception {
        when(httpClient.<String>send(any(HttpRequest.class), any()))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    String url = request.uri().toString();
                    return bodiesByUrlPrefix.entrySet().stream()
                            .filter(entry -> url.startsWith(entry.getKey()))
                            .findFirst()
                            .map(entry -> response(200, entry.getValue(), Map.of()))
                            .orElseGet(() -> response(404, "", Map.of()));
                });
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body, Map<String, List<String>> headers) {
        HttpResponse<String> httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(status);
        when(httpResponse.body()).thenReturn(body);
        when(httpResponse.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
        return httpResponse;
    }

    private String readFixture(String name) throws IOException {
        String path = "fb2top/" + name;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(stream).as("Fixture %s must exist", path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
