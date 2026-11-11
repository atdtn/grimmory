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
 * Opt-in smoke test against the real fantasy-worlds.org site.
 *
 * <p>Run with {@code FANTASY_WORLDS_LIVE_TEST=true ./gradlew test --tests ...}.
 * The parser honours the site's Crawl-delay: 10, so this takes about 40 seconds.</p>
 */
@EnabledIfEnvironmentVariable(named = "FANTASY_WORLDS_LIVE_TEST", matches = "true")
class FantasyWorldsParserLiveSmokeTest {

    @Test
    @Timeout(180)
    void searchesRequiredSampleQueriesAndFetchesDetails() {
        AppSettingService appSettingService = mock(AppSettingService.class);
        AppSettings appSettings = new AppSettings();
        MetadataProviderSettings settings = new MetadataProviderSettings();
        MetadataProviderSettings.FantasyWorlds fantasyWorlds = new MetadataProviderSettings.FantasyWorlds();
        fantasyWorlds.setEnabled(true);
        settings.setFantasyWorlds(fantasyWorlds);
        appSettings.setMetadataProviderSettings(settings);
        when(appSettingService.getAppSettings()).thenReturn(appSettings);

        FantasyWorldsParser parser = new FantasyWorldsParser(
                new ObjectMapper(),
                appSettingService,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());

        List<FetchMetadataRequest> queries = List.of(
                FetchMetadataRequest.builder().title("Дюна").build(),
                FetchMetadataRequest.builder().title("Мастер и Маргарита").build(),
                FetchMetadataRequest.builder().author("Булгаков").build()
        );

        BookMetadata firstResult = null;
        for (FetchMetadataRequest query : queries) {
            List<BookMetadata> results = parser.fetchMetadata(
                    Book.builder().title(query.getTitle()).build(), query);
            assertThat(results).as("Fantasy Worlds results for %s", query).isNotEmpty();

            BookMetadata first = results.getFirst();
            if (firstResult == null) {
                firstResult = first;
            }
            System.out.printf("Fantasy Worlds live search %s -> %d results, first: %s / %s (%s)%n",
                    query.getTitle() != null ? query.getTitle() : query.getAuthor(),
                    results.size(), first.getTitle(), first.getAuthors(), first.getPublishedDate());
        }

        assertThat(firstResult).isNotNull();
        BookMetadata details = parser.fetchDetailedMetadata(firstResult.getFantasyWorldsId());
        assertThat(details).isNotNull();
        assertThat(details.getTitle()).isNotBlank();
        System.out.printf("Fantasy Worlds live details: %s / %s / rating %s%n",
                details.getTitle(), details.getAuthors(), details.getFantasyWorldsRating());
    }
}
