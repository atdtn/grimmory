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
