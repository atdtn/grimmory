package org.booklore.service.metadata.parser;

import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.MetadataProviderSettings;
import org.booklore.service.appsettings.AppSettingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Opt-in smoke test against the real labirint.ru site.
 *
 * <p>Run with {@code LABIRINT_LIVE_TEST=true ./gradlew test --tests ...}.</p>
 */
@EnabledIfEnvironmentVariable(named = "LABIRINT_LIVE_TEST", matches = "true")
class LabirintParserLiveSmokeTest {

    @Test
    @Timeout(180)
    void searchesRequiredSampleQueriesAndFetchesDetails() {
        AppSettingService appSettingService = mock(AppSettingService.class);
        AppSettings appSettings = new AppSettings();
        MetadataProviderSettings settings = new MetadataProviderSettings();
        MetadataProviderSettings.Labirint labirint = new MetadataProviderSettings.Labirint();
        labirint.setEnabled(true);
        settings.setLabirint(labirint);
        appSettings.setMetadataProviderSettings(settings);
        when(appSettingService.getAppSettings()).thenReturn(appSettings);

        LabirintParser parser = new LabirintParser(
                appSettingService,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());

        List<FetchMetadataRequest> queries = List.of(
                FetchMetadataRequest.builder().title("Мастер и Маргарита").build(),
                FetchMetadataRequest.builder().isbn("978-5-00112-063-6").build(),
                FetchMetadataRequest.builder().author("Булгаков").build()
        );

        BookMetadata firstResult = null;
        for (FetchMetadataRequest query : queries) {
            List<BookMetadata> results = parser.fetchMetadata(
                    Book.builder().title(query.getTitle()).build(), query);
            assertThat(results).as("Labirint results for %s", query).isNotEmpty();

            BookMetadata first = results.getFirst();
            if (firstResult == null) {
                firstResult = first;
            }
            if (query.getIsbn() != null) {
                assertThat(first.getIsbn13()).isEqualTo("9785001120636");
            }
            if ("Мастер и Маргарита".equals(query.getTitle())) {
                assertThat(first.getIsbn13()).isNotNull();
                assertThat(first.getPublisher()).isNotBlank();
                assertThat(first.getPageCount()).isGreaterThan(0);
                assertThat(first.getLabirintRating()).isNotNull();
            }
            System.out.printf("Labirint live search %s -> %d results, first: %s / %s, ISBN %s, pages %s%n",
                    query.getTitle() != null ? query.getTitle() : (query.getAuthor() != null ? query.getAuthor() : query.getIsbn()),
                    results.size(), first.getTitle(), first.getAuthors(),
                    first.getIsbn13(), first.getPageCount());
        }

        assertThat(firstResult).isNotNull();
        BookMetadata details = parser.fetchDetailedMetadata(firstResult.getLabirintId());
        assertThat(details).isNotNull();
        assertThat(details.getIsbn13()).isNotNull();
        assertThat(details.getPublisher()).isNotBlank();
        assertThat(details.getPageCount()).isGreaterThan(0);
        assertThat(details.getLabirintRating()).isNotNull();
        System.out.printf("Labirint live details: %s / %s / publisher %s / rating %s%n",
                details.getTitle(), details.getAuthors(), details.getPublisher(), details.getLabirintRating());
    }
}
