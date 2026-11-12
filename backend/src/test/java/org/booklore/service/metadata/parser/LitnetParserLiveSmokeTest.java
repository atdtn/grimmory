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
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Opt-in smoke test against the real litnet.com site.
 *
 * <p>Run with {@code LITNET_LIVE_TEST=true ./gradlew test --tests ...}.</p>
 */
@EnabledIfEnvironmentVariable(named = "LITNET_LIVE_TEST", matches = "true")
class LitnetParserLiveSmokeTest {

    @Test
    @Timeout(120)
    void searchesRequiredSampleQueriesAndFetchesDetails() {
        AppSettingService appSettingService = mock(AppSettingService.class);
        AppSettings appSettings = new AppSettings();
        MetadataProviderSettings settings = new MetadataProviderSettings();
        MetadataProviderSettings.Litnet litnet = new MetadataProviderSettings.Litnet();
        litnet.setEnabled(true);
        settings.setLitnet(litnet);
        appSettings.setMetadataProviderSettings(settings);
        when(appSettingService.getAppSettings()).thenReturn(appSettings);

        LitnetParser parser = new LitnetParser(
                new ObjectMapper(),
                appSettingService,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());

        List<FetchMetadataRequest> queries = List.of(
                FetchMetadataRequest.builder().title("Мастер и Маргарита").build(),
                FetchMetadataRequest.builder().title("Мастер клинков").build(),
                FetchMetadataRequest.builder().author("Булгаков").build()
        );

        BookMetadata firstResult = null;
        for (FetchMetadataRequest query : queries) {
            List<BookMetadata> results = parser.fetchMetadata(
                    Book.builder().title(query.getTitle()).build(), query);
            assertThat(results).as("Litnet results for %s", query).isNotEmpty();

            BookMetadata first = results.getFirst();
            if (firstResult == null) {
                firstResult = first;
            }
            System.out.printf("Litnet live search %s -> %d results, first: %s / %s (%s)%n",
                    query.getTitle() != null ? query.getTitle() : query.getAuthor(),
                    results.size(), first.getTitle(), first.getAuthors(), first.getPublishedDate());
        }

        assertThat(firstResult).isNotNull();
        BookMetadata details = parser.fetchDetailedMetadata(firstResult.getLitnetId());
        assertThat(details).isNotNull();
        assertThat(details.getTitle()).isNotBlank();
        System.out.printf("Litnet live details: %s / %s / rating %s / reviews %s%n",
                details.getTitle(), details.getAuthors(),
                details.getLitnetRating(), details.getLitnetReviewCount());
    }
}
