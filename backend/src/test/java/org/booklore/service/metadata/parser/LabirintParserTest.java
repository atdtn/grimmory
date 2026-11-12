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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LabirintParserTest {

    @Mock
    private AppSettingService appSettingService;

    @Mock
    private HttpClient httpClient;

    private LabirintParser parser;
    private String searchMasterHtml;
    private String detailMasterHtml;

    @BeforeEach
    void setUp() throws IOException {
        parser = new LabirintParser(appSettingService, httpClient, 0L);
        searchMasterHtml = readFixture("search-master.html");
        detailMasterHtml = readFixture("detail-master.html");
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
    void fetchMetadata_parsesSearchCardsAndFiltersNonBookMerch() throws Exception {
        mockSettings(true);
        mockHttp(Map.of(
                "https://www.labirint.ru/search/", searchMasterHtml,
                "https://www.labirint.ru/books/595815/", detailMasterHtml));

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("Мастер и Маргарита").build(),
                FetchMetadataRequest.builder().title("Мастер и Маргарита").build());

        assertThat(results).hasSize(2);
        assertThat(results).extracting(BookMetadata::getTitle)
                .doesNotContain("Игра настольная Мастер и Маргарита");

        BookMetadata first = results.getFirst();
        assertThat(first.getProvider()).isEqualTo(MetadataProvider.LABIRINT);
        assertThat(first.getLabirintId()).isEqualTo("595815");
        assertThat(first.getTitle()).isEqualTo("Мастер и Маргарита");
        assertThat(first.getAuthors()).containsExactly("Булгаков Михаил Афанасьевич");
        assertThat(first.getPublisher()).isEqualTo("Время");
        assertThat(first.getSeriesName()).isEqualTo("Сквозь время");
        assertThat(first.getPageCount()).isEqualTo(464);
        assertThat(first.getIsbn13()).isEqualTo("9785001120636");
        assertThat(first.getPublishedDate()).isEqualTo(LocalDate.of(2017, 1, 1));
        assertThat(first.getLabirintRating()).isEqualTo(4.5);
        assertThat(first.getLabirintReviewCount()).isEqualTo(10);
        assertThat(first.getThumbnailUrl()).isEqualTo("https://imo10.labirint.ru/books/595815/cover.jpg/484-0");
        assertThat(first.getExternalUrl()).isEqualTo("https://www.labirint.ru/books/595815/");
        assertThat(first.getDescription()).contains("Известный литератор")
                .doesNotContain("<br")
                .doesNotContain("Свернуть");
        assertThat(first.getCategories()).contains("Художественная литература",
                "Классическая проза", "Классическая отечественная проза");
    }

    @Test
    void fetchMetadata_searchesByIsbn() throws Exception {
        mockSettings(true);
        mockHttp(Map.of(
                "https://www.labirint.ru/search/", searchMasterHtml,
                "https://www.labirint.ru/books/595815/", detailMasterHtml));

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("Мастер и Маргарита").build(),
                FetchMetadataRequest.builder().isbn("978-5-00112-063-6").build());

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getIsbn13()).isEqualTo("9785001120636");
    }

    @Test
    void fetchMetadata_returnsEmptyWhenIsbnDoesNotMatchAnyResult() throws Exception {
        mockSettings(true);
        mockHttp(Map.of(
                "https://www.labirint.ru/search/", searchMasterHtml,
                "https://www.labirint.ru/books/595815/", detailMasterHtml));

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("Мастер и Маргарита").build(),
                FetchMetadataRequest.builder().isbn("9780000000000").build());

        assertThat(results).isEmpty();
    }

    @Test
    void fetchMetadata_retriesAuthorWithoutPatronymic() throws Exception {
        mockSettings(true);
        List<HttpRequest> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(httpClient.<String>send(any(HttpRequest.class), any()))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    requests.add(request);
                    String url = request.uri().toString();
                    if (url.contains("/search/")) {
                        String decoded = java.net.URLDecoder.decode(url, StandardCharsets.UTF_8);
                        if (decoded.contains("Васильевна")) {
                            return response(200, "<html><body></body></html>", Map.of());
                        }
                        return response(200, searchMasterHtml, Map.of());
                    }
                    return response(200, detailMasterHtml, Map.of());
                });

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("").build(),
                FetchMetadataRequest.builder().author("Галина Васильевна Герасимова").build());

        assertThat(results).isNotEmpty();
        assertThat(requests).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void fetchMetadata_keepsCardFieldsMissingFromDetail() throws Exception {
        mockSettings(true);
        String minimalDetail = """
                <!DOCTYPE html><html><head>
                  <meta itemprop="isbn" content="978-5-00112-063-6">
                </head><body>
                  <h1 itemprop="name">Мастер и Маргарита: Михаил Булгаков</h1>
                  <div class="feature"><div class="name">Автор</div>
                    <div><a href="https://www.labirint.ru/authors/11745/">Булгаков Михаил Афанасьевич</a></div>
                  </div>
                  <div id="annotation"><div>Аннотация с детальной страницы.</div></div>
                </body></html>
                """;
        mockHttp(Map.of(
                "https://www.labirint.ru/search/", searchMasterHtml,
                "https://www.labirint.ru/books/595815/", minimalDetail));

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("Мастер и Маргарита").build(),
                FetchMetadataRequest.builder().title("Мастер и Маргарита").build());

        assertThat(results).isNotEmpty();
        BookMetadata first = results.getFirst();
        assertThat(first.getPublisher()).isEqualTo("Время");
        assertThat(first.getSeriesName()).isEqualTo("Сквозь время");
        assertThat(first.getThumbnailUrl()).contains("cover.jpg/363-0");
        assertThat(first.getIsbn13()).isEqualTo("9785001120636");
    }

    @Test
    void fetchDetailedMetadata_parsesDetailWithoutHashedClasses() throws Exception {
        mockSettings(true);
        mockHttp(Map.of("https://www.labirint.ru/books/595815/", detailMasterHtml));

        BookMetadata result = parser.fetchDetailedMetadata("595815");

        assertThat(result).isNotNull();
        assertThat(result.getLabirintId()).isEqualTo("595815");
        assertThat(result.getTitle()).isEqualTo("Мастер и Маргарита");
        assertThat(result.getAuthors()).containsExactly("Булгаков Михаил Афанасьевич");
        assertThat(result.getPublisher()).isEqualTo("Время");
        assertThat(result.getSeriesName()).isEqualTo("Сквозь время");
        assertThat(result.getPageCount()).isEqualTo(464);
        assertThat(result.getIsbn13()).isEqualTo("9785001120636");
        assertThat(result.getPublishedDate()).isEqualTo(LocalDate.of(2017, 1, 1));
        assertThat(result.getLabirintRating()).isEqualTo(4.5);
        assertThat(result.getLabirintReviewCount()).isEqualTo(10);
        assertThat(result.getCategories()).contains("Классическая отечественная проза");
    }

    @Test
    void fetchDetailedMetadata_retriesOnTooManyRequests() throws Exception {
        mockSettings(true);
        HttpResponse<String> tooManyRequests =
                response(429, "", Map.of("Retry-After", List.of("0")));
        HttpResponse<String> ok = response(200, detailMasterHtml, Map.of());
        when(httpClient.<String>send(any(HttpRequest.class), any()))
                .thenReturn(tooManyRequests, ok);

        BookMetadata result = parser.fetchDetailedMetadata("595815");

        assertThat(result).isNotNull();
        verify(httpClient, times(2)).send(any(HttpRequest.class), any());
    }

    @Test
    void metadataProviderSerializesAsFrontendId() {
        assertThat(new tools.jackson.databind.ObjectMapper()
                .writeValueAsString(MetadataProvider.LABIRINT))
                .isEqualTo("\"Labirint\"");
    }

    private void mockSettings(boolean enabled) {
        AppSettings appSettings = new AppSettings();
        MetadataProviderSettings providerSettings = new MetadataProviderSettings();
        MetadataProviderSettings.Labirint labirint = new MetadataProviderSettings.Labirint();
        labirint.setEnabled(enabled);
        providerSettings.setLabirint(labirint);
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
        String path = "labirint/" + name;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(stream).as("Fixture %s must exist", path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
