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
 * Opt-in smoke test against the real LitRes API.
 *
 * <p>Run with {@code LITRES_LIVE_TEST=true ./gradlew test --tests ...}. Kept out
 * of normal CI so a LitRes outage cannot break the build.</p>
 */
@EnabledIfEnvironmentVariable(named = "LITRES_LIVE_TEST", matches = "true")
class LitresParserLiveSmokeTest {

    @Test
    @Timeout(120)
    void searchesRequiredSampleQueries() {
        AppSettingService appSettingService = mock(AppSettingService.class);
        AppSettings appSettings = new AppSettings();
        MetadataProviderSettings settings = new MetadataProviderSettings();
        MetadataProviderSettings.Litres litres = new MetadataProviderSettings.Litres();
        litres.setEnabled(true);
        settings.setLitres(litres);
        appSettings.setMetadataProviderSettings(settings);
        when(appSettingService.getAppSettings()).thenReturn(appSettings);

        LitresParser parser = new LitresParser(
                new ObjectMapper(),
                appSettingService,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());

        List<FetchMetadataRequest> queries = List.of(
                FetchMetadataRequest.builder().title("Дюна").build(),
                FetchMetadataRequest.builder().author("Сергей Лукьяненко").build(),
                FetchMetadataRequest.builder().title("Мастер и Маргарита").build()
        );

        for (FetchMetadataRequest query : queries) {
            List<BookMetadata> results = parser.fetchMetadata(
                    Book.builder().title(query.getTitle()).build(), query);
            assertThat(results).as("LitRes results for %s", query).isNotEmpty();

            BookMetadata first = results.getFirst();
            System.out.printf("LitRes live search %s -> %d results, first: %s / %s (%s)%n",
                    query.getTitle() != null ? query.getTitle() : query.getAuthor(),
                    results.size(), first.getTitle(), first.getAuthors(),
                    first.getPublishedDate());
        }
    }
}
