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
class LitresParserTest {

    @Mock
    private AppSettingService appSettingService;

    @Mock
    private HttpClient httpClient;

    private LitresParser parser;
    private String searchDuneJson;
    private String searchAuthorJson;
    private String detailDuneJson;
    private String detailAuthorJson;

    @BeforeEach
    void setUp() throws IOException {
        parser = new LitresParser(new ObjectMapper(), appSettingService, httpClient);
        searchDuneJson = readFixture("search-dune.json");
        searchAuthorJson = readFixture("search-author.json");
        detailDuneJson = readFixture("detail-dune.json");
        detailAuthorJson = readFixture("detail-author.json");
    }

    @Test
    void fetchMetadata_returnsEmptyWhenDisabled() {
        mockSettings(false);

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("Дюна").build(),
                FetchMetadataRequest.builder().title("Дюна").build());

        assertThat(results).isEmpty();
        verifyNoInteractions(httpClient);
    }

    @Test
    void fetchMetadata_returnsEmptyForEmptyQuery() {
        mockSettings(true);

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("Дюна").build(),
                FetchMetadataRequest.builder().build());

        assertThat(results).isEmpty();
        verifyNoInteractions(httpClient);
    }

    @Test
    void fetchMetadata_mapsFullLitresMetadataForTitleSearch() throws Exception {
        mockSettings(true);
        mockHttp(Map.of(
                "https://api.litres.ru/foundation/api/search", searchDuneJson,
                "https://api.litres.ru/foundation/api/arts/55339991", detailDuneJson));

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("Дюна").build(),
                FetchMetadataRequest.builder().title("Дюна").author("Фрэнк Герберт").build());

        assertThat(results).hasSize(1);
        BookMetadata result = results.getFirst();

        assertThat(result.getProvider()).isEqualTo(MetadataProvider.Litres);
        assertThat(result.getLitresId()).isEqualTo("55339991");
        assertThat(result.getTitle()).isEqualTo("Дюна");
        assertThat(result.getSubtitle()).isNull();
        assertThat(result.getAuthors()).containsExactly("Фрэнк Герберт", "Юрий Соколов");
        assertThat(result.getDescription()).startsWith("Роман «Дюна», первая книга прославленной саги");
        assertThat(result.getThumbnailUrl()).isEqualTo("https://cdn2.litres.ru/pub/c/cover/55339991.jpg");
        assertThat(result.getExternalUrl()).isEqualTo("https://www.litres.ru/book/frenk-gerbert/duna-55339991/");
        assertThat(result.getCategories()).contains("космическая фантастика", "зарубежная фантастика");
        assertThat(result.getTags()).contains("экранизации", "космоопера");
        assertThat(result.getSeriesName()).isEqualTo("Хроники Дюны");
        assertThat(result.getSeriesNumber()).isEqualTo(1.0f);
        assertThat(result.getSeriesTotal()).isEqualTo(6);
        assertThat(result.getPublishedDate()).isEqualTo(LocalDate.of(1965, 1, 1));
        assertThat(result.getIsbn13()).isEqualTo("9785171189334");
        assertThat(result.getIsbn10()).isNull();
        assertThat(result.getPageCount()).isEqualTo(694);
        assertThat(result.getLanguage()).isEqualTo("ru");
        assertThat(result.getPublisher()).isEqualTo("Издательство АСТ");
        assertThat(result.getLitresRating()).isEqualTo(4.6);
        assertThat(result.getLitresReviewCount()).isEqualTo(2406);
    }

    @Test
    void fetchMetadata_supportsAuthorOnlySearch() throws Exception {
        mockSettings(true);
        mockHttp(Map.of(
                "https://api.litres.ru/foundation/api/search", searchAuthorJson,
                "https://api.litres.ru/foundation/api/arts/72497368", detailAuthorJson));

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("").build(),
                FetchMetadataRequest.builder().author("Сергей Лукьяненко").build());

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getTitle()).isEqualTo("Девятый");
        assertThat(results.getFirst().getAuthors()).containsExactly("Сергей Лукьяненко");
        assertThat(results.getFirst().getSeriesName()).isEqualTo("Небесное воинство");
        assertThat(results.getFirst().getSeriesNumber()).isEqualTo(2.0f);
    }

    @Test
    void fetchMetadata_searchesByIsbnAndValidatesIt() throws Exception {
        mockSettings(true);
        mockHttp(Map.of(
                "https://api.litres.ru/foundation/api/search", searchDuneJson,
                "https://api.litres.ru/foundation/api/arts/55339991", detailDuneJson));

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("Дюна").build(),
                FetchMetadataRequest.builder().isbn("978-5-17-118933-4").build());

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getIsbn13()).isEqualTo("9785171189334");
    }

    @Test
    void fetchDetailedMetadata_mapsBookById() throws Exception {
        mockSettings(true);
        mockHttp(Map.of("https://api.litres.ru/foundation/api/arts/55339991", detailDuneJson));

        BookMetadata result = parser.fetchDetailedMetadata("55339991");

        assertThat(result).isNotNull();
        assertThat(result.getLitresId()).isEqualTo("55339991");
        assertThat(result.getTitle()).isEqualTo("Дюна");
    }

    @Test
    void fetchDetailedMetadata_retriesOnTooManyRequests() throws Exception {
        mockSettings(true);
        HttpResponse<String> tooManyRequests = response(429, "", Map.of("Retry-After", List.of("0")));
        HttpResponse<String> ok = response(200, detailDuneJson, Map.of());
        when(httpClient.<String>send(any(HttpRequest.class), any()))
                .thenReturn(tooManyRequests, ok);

        BookMetadata result = parser.fetchDetailedMetadata("55339991");

        assertThat(result).isNotNull();
        verify(httpClient, times(2)).send(any(HttpRequest.class), any());
    }

    @Test
    void fetchDetailedMetadata_doesNotRetryClientErrors() throws Exception {
        mockSettings(true);
        HttpResponse<String> badRequest = response(400, "", Map.of());
        when(httpClient.<String>send(any(HttpRequest.class), any()))
                .thenReturn(badRequest);

        BookMetadata result = parser.fetchDetailedMetadata("55339991");

        assertThat(result).isNull();
        verify(httpClient, times(1)).send(any(HttpRequest.class), any());
    }

    @Test
    void fetchMetadata_returnsEmptyWhenSearchHasNoResults() throws Exception {
        mockSettings(true);
        mockHttp(Map.of("https://api.litres.ru/foundation/api/search",
                "{\"status\":200,\"payload\":{\"data\":[]}}"));

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("Неизвестная книга").build(),
                FetchMetadataRequest.builder().title("Неизвестная книга").build());

        assertThat(results).isEmpty();
    }

    private void mockSettings(boolean enabled) {
        AppSettings appSettings = new AppSettings();
        MetadataProviderSettings providerSettings = new MetadataProviderSettings();
        MetadataProviderSettings.Litres litres = new MetadataProviderSettings.Litres();
        litres.setEnabled(enabled);
        providerSettings.setLitres(litres);
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
        String path = "litres/" + name;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(stream).as("Fixture %s must exist", path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
