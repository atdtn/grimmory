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
import tools.jackson.databind.ObjectMapper;

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
class LitnetParserTest {

    @Mock
    private AppSettingService appSettingService;

    @Mock
    private HttpClient httpClient;

    private LitnetParser parser;
    private String searchMasterJson;
    private String detailMasterHtml;

    @BeforeEach
    void setUp() throws IOException {
        parser = new LitnetParser(new ObjectMapper(), appSettingService, httpClient, 0L);
        searchMasterJson = readFixture("search-master.json");
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
    void fetchMetadata_returnsEmptyForIsbnOnlyBecauseLitnetHasNoIsbn() {
        mockSettings(true);

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("Мастер и Маргарита").build(),
                FetchMetadataRequest.builder().isbn("9785171189334").build());

        assertThat(results).isEmpty();
        verifyNoInteractions(httpClient);
    }

    @Test
    void fetchMetadata_mapsSearchState() throws Exception {
        mockSettings(true);
        mockHttp(Map.of("https://litnet.com/ru/search", htmlWithNgState(searchMasterJson)));

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("Мастер и Маргарита").build(),
                FetchMetadataRequest.builder().title("Мастер и Маргарита").build());

        assertThat(results).hasSize(1);
        BookMetadata result = results.getFirst();
        assertThat(result.getProvider()).isEqualTo(MetadataProvider.LITNET);
        assertThat(result.getLitnetId()).isEqualTo("445406");
        assertThat(result.getTitle()).isEqualTo("Мастер и Маргарита");
        assertThat(result.getAuthors()).containsExactly("Редактор");
        assertThat(result.getDescription()).startsWith("Роман «Мастер и Маргарита»");
        assertThat(result.getThumbnailUrl())
                .isEqualTo("https://publiccdn.litnet.com/books/covers/0/1690205193_64.jpg");
        assertThat(result.getExternalUrl())
                .isEqualTo("https://litnet.com/ru/book/master-i-margarita-b445406");
        assertThat(result.getPublishedDate()).isEqualTo(LocalDate.of(2023, 7, 24));
        assertThat(result.getPageCount()).isEqualTo(373);
        assertThat(result.getCategories()).containsExactly("Проза");
        assertThat(result.getTags()).containsExactly("классическая литература");
        assertThat(result.getLitnetRating()).isNull();
        assertThat(result.getLitnetReviewCount()).isEqualTo(176);
        assertThat(result.getIsbn13()).isNull();
        assertThat(result.getIsbn10()).isNull();
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
                    if (url.contains("/search")) {
                        String query = request.uri().getQuery();
                        if (query != null && query.contains("Васильевна")) {
                            return response(200, htmlWithNgState(
                                    "{\"items-books-1-x\":[]}"), Map.of());
                        }
                        return response(200, htmlWithNgState(searchMasterJson), Map.of());
                    }
                    return response(200, detailMasterHtml, Map.of());
                });

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("").build(),
                FetchMetadataRequest.builder().author("Галина Васильевна Герасимова").build());

        assertThat(results).isNotEmpty();
        assertThat(requests.stream()
                .map(request -> request.uri().getQuery())
                .filter(query -> query != null && query.contains("Герасимова")))
                .isNotEmpty();
    }

    @Test
    void fetchMetadata_searchesByTitleOnlyWhenAuthorIsProvided() throws Exception {
        mockSettings(true);
        List<HttpRequest> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(httpClient.<String>send(any(HttpRequest.class), any()))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    requests.add(request);
                    String url = request.uri().toString();
                    return url.contains("/search")
                            ? response(200, htmlWithNgState(searchMasterJson), Map.of())
                            : response(200, detailMasterHtml, Map.of());
                });

        parser.fetchMetadata(
                Book.builder().title("Мастер и Маргарита").build(),
                FetchMetadataRequest.builder()
                        .title("Мастер и Маргарита")
                        .author("Михаил Булгаков")
                        .build());

        assertThat(requests).isNotEmpty();
        String rawQuery = requests.getFirst().uri().getRawQuery();
        assertThat(rawQuery).contains("q=%D0%9C%D0%B0%D1%81%D1%82%D0%B5%D1%80");
        assertThat(rawQuery).doesNotContain("%D0%91%D1%83%D0%BB%D0%B3%D0%B0%D0%BA%D0%BE%D0%B2");
    }

    @Test
    void fetchMetadata_hydratesTopResultsWithDetailMetadata() throws Exception {
        mockSettings(true);
        mockHttp(Map.of(
                "https://litnet.com/ru/search", htmlWithNgState(searchMasterJson),
                "https://litnet.com/ru/book/x-b445406", detailMasterHtml));

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("Мастер и Маргарита").build(),
                FetchMetadataRequest.builder().title("Мастер и Маргарита").build());

        assertThat(results).hasSize(1);
        BookMetadata result = results.getFirst();
        assertThat(result.getAuthors()).containsExactly("Михаил Булгаков");
        assertThat(result.getLitnetRating()).isEqualTo(4.0);
        assertThat(result.getPublisher()).isEqualTo("Литнет Паблишинг");
    }

    @Test
    void fetchMetadata_filtersIrrelevantSearchResults() throws Exception {
        mockSettings(true);
        String state = """
                {"items-books-1-Мастер":[
                  {"id":1,"title":"Совсем другая книга","author":{"name":"Другой Автор"},
                   "description":"","image":"https://publiccdn.litnet.com/books/covers/220/1.jpg",
                   "link":"https://litnet.com/ru/book/other-b1","firstPublicationDate":"2020-01-01"},
                  {"id":2,"title":"Мастер и Маргарита","author":{"name":"Михаил Булгаков"},
                   "description":"","image":"https://publiccdn.litnet.com/books/covers/220/2.jpg",
                   "link":"https://litnet.com/ru/book/master-b2","firstPublicationDate":"2023-07-24"}
                ]}
                """;
        mockHttp(Map.of("https://litnet.com/ru/search", htmlWithNgState(state)));

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("Мастер и Маргарита").build(),
                FetchMetadataRequest.builder().title("Мастер и Маргарита").build());

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getLitnetId()).isEqualTo("2");
    }

    @Test
    void fetchDetailedMetadata_usesNgStateAndJsonLd() throws Exception {
        mockSettings(true);
        mockHttp(Map.of("https://litnet.com/ru/book/x-b445406", detailMasterHtml));

        BookMetadata result = parser.fetchDetailedMetadata("445406");

        assertThat(result).isNotNull();
        assertThat(result.getLitnetId()).isEqualTo("445406");
        assertThat(result.getTitle()).isEqualTo("Мастер и Маргарита");
        assertThat(result.getAuthors()).containsExactly("Михаил Булгаков");
        assertThat(result.getDescription()).startsWith("Роман «Мастер и Маргарита»");
        assertThat(result.getThumbnailUrl())
                .isEqualTo("https://publiccdn.litnet.com/books/covers/0/1690205193_64.jpg");
        assertThat(result.getPublisher()).isEqualTo("Литнет Паблишинг");
        assertThat(result.getPageCount()).isEqualTo(373);
        assertThat(result.getPublishedDate()).isEqualTo(LocalDate.of(2023, 7, 24));
        assertThat(result.getCategories()).containsExactly("Проза");
        assertThat(result.getTags()).containsExactly("классическая литература");
        assertThat(result.getLitnetRating()).isEqualTo(4.0);
        assertThat(result.getLitnetReviewCount()).isEqualTo(176);
        assertThat(result.getIsbn13()).isNull();
    }

    @Test
    void fetchDetailedMetadata_mapsCycleWhenPresent() throws Exception {
        mockSettings(true);
        String html = detailHtmlWithCycle("""
                {"id":777,"title":"Книга цикла","annotation":"Описание","image":"https://publiccdn.litnet.com/books/covers/220/7.jpg",
                 "image_original":"https://publiccdn.litnet.com/books/covers/0/7.jpg",
                 "author":{"name":"Автор Цикла"},"genres":[{"name":"Фэнтези"}],"tags":[],
                 "cycle":{"name":"Большой цикл"},"priority_cycle":3,"pages":100,"published_at":"2024-01-02",
                 "publisher":{"publisher_title":"Издатель"}}
                """, "Книга цикла");
        mockHttp(Map.of("https://litnet.com/ru/book/x-b777", html));

        BookMetadata result = parser.fetchDetailedMetadata("777");

        assertThat(result).isNotNull();
        assertThat(result.getSeriesName()).isEqualTo("Большой цикл");
        assertThat(result.getSeriesNumber()).isEqualTo(3.0f);
    }

    @Test
    void fetchDetailedMetadata_mapsCamelCasePageNode() throws Exception {
        mockSettings(true);
        String html = """
                <!DOCTYPE html><html><head><script type="application/ld+json">
                {"@context":"https://schema.org","@type":"Book","name":"Страничная книга",
                 "aggregateRating":{"ratingValue":4.5,"ratingCount":10}}
                </script></head><body><script id="ng-state" type="application/json">
                {"book-page-main":{"data":{"page":{"id":888,"title":"Страничная книга",
                  "annotation":"Страничное описание","imageOriginal":"https://cdn/cover-original.jpg",
                  "author":{"name":"Автор Страницы"},"countOfPages":250,
                  "firstPublicationDate":"2022-02-02","priorityInCycle":0,
                  "tagList":[{"name":"тег страницы"}],
                  "publisher":{"title":"Страничный издатель"}}}}}
                </script></body></html>
                """;
        mockHttp(Map.of("https://litnet.com/ru/book/x-b888", html));

        BookMetadata result = parser.fetchDetailedMetadata("888");

        assertThat(result).isNotNull();
        assertThat(result.getTitle()).isEqualTo("Страничная книга");
        assertThat(result.getPageCount()).isEqualTo(250);
        assertThat(result.getPublisher()).isEqualTo("Страничный издатель");
        assertThat(result.getTags()).containsExactly("тег страницы");
        assertThat(result.getThumbnailUrl()).isEqualTo("https://cdn/cover-original.jpg");
    }

    @Test
    void fetchDetailedMetadata_fallsBackToJsonLdOnly() throws Exception {
        mockSettings(true);
        String html = """
                <!DOCTYPE html><html><head><script type="application/ld+json">
                {"@context":"https://schema.org","@type":"Book","name":"Только JSON-LD",
                 "author":{"@type":"Person","name":"Автор JSON"},
                 "description":"Описание из JSON-LD","datePublished":"2021-03-04",
                 "image":"https://cdn/json-cover.jpg","genre":["Фэнтези"],
                 "aggregateRating":{"ratingValue":4.25,"ratingCount":12}}
                </script></head><body></body></html>
                """;
        mockHttp(Map.of("https://litnet.com/ru/book/x-b999", html));

        BookMetadata result = parser.fetchDetailedMetadata("999");

        assertThat(result).isNotNull();
        assertThat(result.getTitle()).isEqualTo("Только JSON-LD");
        assertThat(result.getAuthors()).containsExactly("Автор JSON");
        assertThat(result.getPublishedDate()).isEqualTo(LocalDate.of(2021, 3, 4));
        assertThat(result.getCategories()).containsExactly("Фэнтези");
        assertThat(result.getLitnetRating()).isEqualTo(4.25);
        assertThat(result.getLitnetReviewCount()).isEqualTo(12);
    }

    @Test
    void fetchDetailedMetadata_mapsCoAuthor() throws Exception {
        mockSettings(true);
        String book = """
                {"id":555,"title":"Книга с соавтором","annotation":"Описание",
                 "image":"https://cdn/555.jpg","author":{"name":"Основной Автор"},
                 "co_author":{"name":"Соавтор"},"pages":10,"published_at":"2020-01-01"}
                """;
        mockHttp(Map.of("https://litnet.com/ru/book/x-b555", detailHtmlWithBook("555", book, "Книга с соавтором")));

        BookMetadata result = parser.fetchDetailedMetadata("555");

        assertThat(result).isNotNull();
        assertThat(result.getAuthors()).containsExactly("Основной Автор", "Соавтор");
    }

    @Test
    void fetchDetailedMetadata_sendsCookiesFromFailedAttempt() throws Exception {
        mockSettings(true);
        HttpResponse<String> challenge = response(200, "Just a moment...",
                Map.of("Set-Cookie", List.of("__cf_bm=abc; Path=/; HttpOnly")));
        HttpResponse<String> ok = response(200, detailMasterHtml, Map.of());
        List<HttpRequest> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(httpClient.<String>send(any(HttpRequest.class), any()))
                .thenAnswer(invocation -> {
                    requests.add(invocation.getArgument(0));
                    return requests.size() == 1 ? challenge : ok;
                });

        BookMetadata result = parser.fetchDetailedMetadata("445406");

        assertThat(result).isNotNull();
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).headers().firstValue("Cookie")).hasValue("__cf_bm=abc");
    }

    @Test
    void fetchDetailedMetadata_retriesCloudflareChallengeAnd429() throws Exception {
        mockSettings(true);
        HttpResponse<String> challenge = response(200, "Just a moment...", Map.of());
        HttpResponse<String> tooManyRequests =
                response(429, "", Map.of("Retry-After", List.of("0")));
        HttpResponse<String> ok = response(200, detailMasterHtml, Map.of());
        when(httpClient.<String>send(any(HttpRequest.class), any()))
                .thenReturn(challenge, tooManyRequests, ok);

        BookMetadata result = parser.fetchDetailedMetadata("445406");

        assertThat(result).isNotNull();
        verify(httpClient, times(3)).send(any(HttpRequest.class), any());
    }

    @Test
    void providerPathVariableNamesAreCaseInsensitive() {
        var converter = new org.booklore.config.WebMvcConfig(null, null, null).metadataProviderConverter();
        assertThat(converter.convert("Litnet")).isEqualTo(MetadataProvider.LITNET);
        assertThat(converter.convert("LITNET")).isEqualTo(MetadataProvider.LITNET);
        assertThat(converter.convert("FantasyWorlds")).isEqualTo(MetadataProvider.FANTASY_WORLDS);
    }

    @Test
    void metadataProviderRoundTripsAsFrontendId() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        assertThat(objectMapper.writeValueAsString(MetadataProvider.LITNET))
                .isEqualTo("\"Litnet\"");
        assertThat(objectMapper.readValue("\"Litnet\"", MetadataProvider.class))
                .isEqualTo(MetadataProvider.LITNET);
    }

    private String htmlWithNgState(String stateJson) {
        return "<!DOCTYPE html><html><body><script id=\"ng-state\" type=\"application/json\">"
                + stateJson
                + "</script></body></html>";
    }

    private String detailHtmlWithCycle(String bookJson, String title) {
        return detailHtmlWithBook("777", bookJson, title);
    }

    private String detailHtmlWithBook(String id, String bookJson, String title) {
        return "<!DOCTYPE html><html><head><script type=\"application/ld+json\">"
                + "{\"@context\":\"https://schema.org\",\"@type\":\"Book\",\"name\":\"" + title
                + "\",\"aggregateRating\":{\"ratingValue\":4.5,\"ratingCount\":10}}"
                + "</script></head><body><script id=\"ng-state\" type=\"application/json\">"
                + "{\"book-page-main\":{\"b\":" + bookJson + "}}"
                + "</script></body></html>";
    }

    private void mockSettings(boolean enabled) {
        AppSettings appSettings = new AppSettings();
        MetadataProviderSettings providerSettings = new MetadataProviderSettings();
        MetadataProviderSettings.Litnet litnet = new MetadataProviderSettings.Litnet();
        litnet.setEnabled(enabled);
        providerSettings.setLitnet(litnet);
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
        String path = "litnet/" + name;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(stream).as("Fixture %s must exist", path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
