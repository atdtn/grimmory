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
class FantasyWorldsParserTest {

    @Mock
    private AppSettingService appSettingService;

    @Mock
    private HttpClient httpClient;

    private FantasyWorldsParser parser;
    private String searchMasterJson;
    private String searchDuneJson;
    private String searchAuthorJson;
    private String detailMasterHtml;
    private String detailDuneHtml;

    @BeforeEach
    void setUp() throws IOException {
        parser = new FantasyWorldsParser(new ObjectMapper(), appSettingService, httpClient, 0L);
        searchMasterJson = readFixture("search-master.json");
        searchDuneJson = readFixture("search-dune.json");
        searchAuthorJson = readFixture("search-author.json");
        detailMasterHtml = readFixture("detail-master.html");
        detailDuneHtml = readFixture("detail-dune.html");
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
    void fetchMetadata_retriesAuthorWithoutPatronymic() throws Exception {
        mockSettings(true);
        String empty = "{\"books\":[],\"authors\":[],\"keywords\":[]}";
        String found = """
                {"books":[{"id":"37868","title":"В поисках того самого",
                  "author_name":"Галина","author_surname":"Герасимова",
                  "year":"2024","ISBN":"978-5-17-175973-5","ratio":"9/10",
                  "genre":"1","lang_code":"ru","book_ext":"jpg"}],
                 "authors":[],"keywords":[]}
                """;
        mockSearchQueries(Map.of("q=Галина Герасимова", found), empty);

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("").build(),
                FetchMetadataRequest.builder().author("Галина Васильевна Герасимова").build());

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getTitle()).isEqualTo("В поисках того самого");
        assertThat(results.getFirst().getAuthors()).containsExactly("Галина Герасимова");
    }

    @Test
    void fetchMetadata_findsBookByTitleWhenAuthorHasPatronymicAndIsbnIsNormalized() throws Exception {
        mockSettings(true);
        String empty = "{\"books\":[],\"authors\":[],\"keywords\":[]}";
        String found = """
                {"books":[{"id":"32814","title":"Ведьмин фонарь",
                  "author_name":"Галина","author_surname":"Герасимова",
                  "year":"2024","ISBN":"978-5-9922-3373-5","ratio":"9/10",
                  "genre":"1","lang_code":"ru","book_ext":"jpg"}],
                 "authors":[],"keywords":[]}
                """;
        mockSearchQueries(Map.of("q=Ведьмин фонарь", found), empty);

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("Ведьмин фонарь").build(),
                FetchMetadataRequest.builder()
                        .title("Ведьмин фонарь")
                        .author("Галина Васильевна Герасимова")
                        .isbn("9785992233735")
                        .build());

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getTitle()).isEqualTo("Ведьмин фонарь");
        assertThat(results.getFirst().getIsbn13()).isEqualTo("9785992233735");
    }

    @Test
    void fetchMetadata_mapsSearchJson() throws Exception {
        mockSettings(true);
        mockHttp(Map.of("https://fantasy-worlds.org/search.json", searchMasterJson));

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("Мастер и Маргарита").build(),
                FetchMetadataRequest.builder().title("Мастер и Маргарита").build());

        assertThat(results).hasSize(1);
        BookMetadata result = results.getFirst();
        assertThat(result.getProvider()).isEqualTo(MetadataProvider.FANTASY_WORLDS);
        assertThat(result.getFantasyWorldsId()).isEqualTo("3543");
        assertThat(result.getTitle()).isEqualTo("Мастер и Маргарита");
        assertThat(result.getAuthors()).containsExactly("Михаил Булгаков");
        assertThat(result.getDescription()).startsWith("Роман «Мастер и Маргарита»");
        assertThat(result.getThumbnailUrl()).isEqualTo("https://fantasy-worlds.org/img/full/35/3543.jpg");
        assertThat(result.getExternalUrl()).isEqualTo("https://fantasy-worlds.org/lib/id3543/");
        assertThat(result.getFantasyWorldsRating()).isEqualTo(4.8);
        assertThat(result.getFantasyWorldsReviewCount()).isEqualTo(58);
        assertThat(result.getLanguage()).isEqualTo("ru");
        assertThat(result.getCategories()).containsExactly("genre:1");
    }

    @Test
    void fetchMetadata_mapsSeriesAndIsbnFromSearchJson() throws Exception {
        mockSettings(true);
        mockHttp(Map.of("https://fantasy-worlds.org/search.json", searchDuneJson));

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("Дюна").build(),
                FetchMetadataRequest.builder().title("Дюна").build());

        assertThat(results).hasSize(2);
        BookMetadata result = results.getFirst();
        assertThat(result.getTitle()).isEqualTo("Капитул Дюны");
        assertThat(result.getSeriesName()).isEqualTo("Хроники Дюны");
        assertThat(result.getSeriesNumber()).isEqualTo(6.0f);
        assertThat(result.getIsbn10()).isEqualTo("5883580440");
        assertThat(result.getPublishedDate()).isEqualTo(LocalDate.of(1985, 1, 1));
        assertThat(result.getFantasyWorldsRating()).isEqualTo(4.6);
        assertThat(result.getFantasyWorldsReviewCount()).isEqualTo(60);
        assertThat(result.getCategories()).containsExactly("genre:25");
        assertThat(results.get(1).getIsbn13()).isEqualTo("9785171371036");
    }

    @Test
    void fetchMetadata_filtersIrrelevantResultsAndSendsQuery() throws Exception {
        mockSettings(true);
        List<HttpRequest> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        String json = """
                {"books":[
                  {"id":"1","title":"Совсем другая книга","author_name":"Другой","author_surname":"Автор",
                   "about":"","genre":"1","book_ext":"jpg","ratio":"5/1","lang_code":"ru"},
                  {"id":"2","title":"Мастер и Маргарита","author_name":"Михаил","author_surname":"Булгаков",
                   "about":"","genre":"1","book_ext":"jpg","ratio":"9.6/58","lang_code":"ru"}
                ],"authors":[],"keywords":[]}
                """;
        when(httpClient.<String>send(any(HttpRequest.class), any()))
                .thenAnswer(invocation -> {
                    requests.add(invocation.getArgument(0));
                    return response(200, json, Map.of());
                });

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("Мастер и Маргарита").build(),
                FetchMetadataRequest.builder().title("Мастер и Маргарита").build());

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getTitle()).isEqualTo("Мастер и Маргарита");
        assertThat(requests.getFirst().uri().getRawQuery())
                .contains("q=%D0%9C%D0%B0%D1%81%D1%82%D0%B5%D1%80");
    }

    @Test
    void fetchMetadata_returnsEmptyWhenAllSearchResultsAreUnrelated() throws Exception {
        mockSettings(true);
        String unrelated = """
                {"books":[{"id":"1","title":"Совсем другая книга","author_name":"Другой",
                  "author_surname":"Автор","year":"2020","ISBN":"","ratio":"5/1",
                  "genre":"1","lang_code":"ru","book_ext":"jpg"}],
                 "authors":[],"keywords":[]}
                """;
        mockHttp(Map.of("https://fantasy-worlds.org/search.json", unrelated));

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("Ведьмин фонарь").build(),
                FetchMetadataRequest.builder().title("Ведьмин фонарь").build());

        assertThat(results).isEmpty();
    }

    @Test
    void fetchMetadata_normalizesIsbn13FromCommaSeparatedValue() throws Exception {
        mockSettings(true);
        String json = """
                {"books":[
                  {"id":"10","title":"Книга","author_name":"Иван","author_surname":"Петров",
                   "about":"","year":"2020","ISBN":" 978-5-17-137103-6, 5-88358-044-0",
                   "genre":"1","book_ext":"jpg","ratio":"8/4","lang_code":"ru"}
                ],"authors":[],"keywords":[]}
                """;
        mockHttp(Map.of("https://fantasy-worlds.org/search.json", json));

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("Книга").build(),
                FetchMetadataRequest.builder().title("Книга").build());

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getIsbn13()).isEqualTo("9785171371036");
        assertThat(results.getFirst().getIsbn10()).isNull();
    }

    @Test
    void fetchMetadata_supportsAuthorSearch() throws Exception {
        mockSettings(true);
        mockHttp(Map.of("https://fantasy-worlds.org/search.json", searchAuthorJson));

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("").build(),
                FetchMetadataRequest.builder().author("Булгаков").build());

        assertThat(results).hasSize(3);
        assertThat(results).extracting(BookMetadata::getTitle)
                .contains("Собачье сердце", "Роковые яйца", "Мастер и Маргарита");
    }

    @Test
    void detailFixtureContainsExpectedMicrodata() throws Exception {
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(detailMasterHtml);
        assertThat(doc.selectFirst("[itemprop=name]")).isNotNull();
        assertThat(doc.selectFirst("[itemprop=description]")).isNotNull();
        assertThat(doc.selectFirst("meta[itemprop=inLanguage]")).isNotNull();
        assertThat(doc.select("a[href^=/lib/tag]")).isNotEmpty();
    }

    @Test
    void fetchDetailedMetadata_parsesHtml() throws Exception {
        mockSettings(true);
        mockHttp(Map.of("https://fantasy-worlds.org/lib/id3543/", detailMasterHtml));

        BookMetadata result = parser.fetchDetailedMetadata("3543");

        assertThat(result).isNotNull();
        assertThat(result.getFantasyWorldsId()).isEqualTo("3543");
        assertThat(result.getTitle()).isEqualTo("Мастер и Маргарита");
        assertThat(result.getAuthors()).containsExactly("Михаил Булгаков");
        assertThat(result.getDescription()).startsWith("Роман «Мастер и Маргарита»");
        assertThat(result.getTags()).contains("христианство", "бессмертие", "философское");
        assertThat(result.getFantasyWorldsRating()).isEqualTo(4.8);
        assertThat(result.getFantasyWorldsReviewCount()).isEqualTo(58);
        assertThat(result.getThumbnailUrl()).isEqualTo("https://fantasy-worlds.org/img/full/35/3543.jpg");
        assertThat(result.getLanguage()).isEqualTo("ru");
    }

    @Test
    void fetchDetailedMetadata_parsesSeriesYearAndIsbn() throws Exception {
        mockSettings(true);
        mockHttp(Map.of("https://fantasy-worlds.org/lib/id5398/", detailDuneHtml));

        BookMetadata result = parser.fetchDetailedMetadata("5398");

        assertThat(result).isNotNull();
        assertThat(result.getTitle()).isEqualTo("Капитул Дюны");
        assertThat(result.getSeriesName()).isEqualTo("Хроники Дюны");
        assertThat(result.getSeriesNumber()).isEqualTo(6.0f);
        assertThat(result.getPublishedDate()).isEqualTo(LocalDate.of(1985, 1, 1));
        assertThat(result.getIsbn10()).isEqualTo("5883580440");
    }

    @Test
    void fetchDetailedMetadata_retriesTooManyRequests() throws Exception {
        mockSettings(true);
        HttpResponse<String> tooManyRequests =
                response(429, "", Map.of("Retry-After", List.of("0")));
        HttpResponse<String> ok = response(200, detailMasterHtml, Map.of());
        when(httpClient.<String>send(any(HttpRequest.class), any()))
                .thenReturn(tooManyRequests, ok);

        BookMetadata result = parser.fetchDetailedMetadata("3543");

        assertThat(result).isNotNull();
        verify(httpClient, times(2)).send(any(HttpRequest.class), any());
    }

    @Test
    void respectsCrawlDelayBetweenRequests() throws Exception {
        FantasyWorldsParser delayedParser =
                new FantasyWorldsParser(new ObjectMapper(), appSettingService, httpClient, 100L);
        mockSettings(true);
        mockHttp(Map.of("https://fantasy-worlds.org/lib/id3543/", detailMasterHtml));

        long startedAt = System.nanoTime();
        delayedParser.fetchDetailedMetadata("3543");
        delayedParser.fetchDetailedMetadata("3543");
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(90L);
    }

    @Test
    void metadataProviderSerializesAsFrontendId() {
        assertThat(new ObjectMapper().writeValueAsString(MetadataProvider.FANTASY_WORLDS))
                .isEqualTo("\"FantasyWorlds\"");
    }

    private void mockSettings(boolean enabled) {
        AppSettings appSettings = new AppSettings();
        MetadataProviderSettings providerSettings = new MetadataProviderSettings();
        MetadataProviderSettings.FantasyWorlds fantasyWorlds = new MetadataProviderSettings.FantasyWorlds();
        fantasyWorlds.setEnabled(enabled);
        providerSettings.setFantasyWorlds(fantasyWorlds);
        appSettings.setMetadataProviderSettings(providerSettings);
        when(appSettingService.getAppSettings()).thenReturn(appSettings);
    }

    private void mockSearchQueries(Map<String, String> bodiesByQuery, String fallbackBody) throws Exception {
        when(httpClient.<String>send(any(HttpRequest.class), any()))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    String query = request.uri().getQuery();
                    String body = query != null && bodiesByQuery.containsKey(query)
                            ? bodiesByQuery.get(query)
                            : fallbackBody;
                    return response(200, body, Map.of());
                });
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
        String path = "fantasyworlds/" + name;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(stream).as("Fixture %s must exist", path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
