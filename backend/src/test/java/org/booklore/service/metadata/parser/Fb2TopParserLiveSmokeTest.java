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
 * Opt-in smoke test against the real fb2.top site.
 *
 * <p>Run with {@code FB2_TOP_LIVE_TEST=true ./gradlew test --tests ...}.</p>
 */
@EnabledIfEnvironmentVariable(named = "FB2_TOP_LIVE_TEST", matches = "true")
class Fb2TopParserLiveSmokeTest {

    private Fb2TopParser parser() {
        AppSettingService appSettingService = mock(AppSettingService.class);
        AppSettings appSettings = new AppSettings();
        MetadataProviderSettings settings = new MetadataProviderSettings();
        MetadataProviderSettings.Fb2Top fb2Top = new MetadataProviderSettings.Fb2Top();
        fb2Top.setEnabled(true);
        settings.setFb2Top(fb2Top);
        appSettings.setMetadataProviderSettings(settings);
        when(appSettingService.getAppSettings()).thenReturn(appSettings);

        return new Fb2TopParser(
                appSettingService,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    @Test
    @Timeout(180)
    void searchesMasterAndMargaritaAndFetchesDetails() {
        Fb2TopParser parser = parser();

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("Мастер и Маргарита").build(),
                FetchMetadataRequest.builder()
                        .title("Мастер и Маргарита")
                        .author("Михаил Булгаков")
                        .build());

        assertThat(results).as("FB2.top results for Мастер и Маргарита").isNotEmpty();
        BookMetadata first = results.getFirst();
        assertThat(first.getTitle()).isEqualTo("Мастер и Маргарита");
        assertThat(first.getAuthors()).anyMatch(author -> author.contains("Булгаков"));
        assertThat(first.getExternalUrl()).startsWith("https://fb2.top/");
        assertThat(first.getIsbn13()).isNull();
        assertThat(first.getIsbn10()).isNull();

        BookMetadata details = parser.fetchDetailedMetadata(first.getFb2TopId());
        assertThat(details).isNotNull();
        assertThat(details.getTitle()).isEqualTo("Мастер и Маргарита");
        assertThat(details.getAuthors()).anyMatch(author -> author.contains("Булгаков"));
        assertThat(details.getPageCount()).isPositive();
        assertThat(details.getFb2TopRating()).isNotNull();
        assertThat(details.getIsbn13()).isNull();
        System.out.printf("FB2.top live details: %s / %s / pages %s / rating %s / cover %s%n",
                details.getTitle(), details.getAuthors(), details.getPageCount(),
                details.getFb2TopRating(), details.getThumbnailUrl());
    }

    @Test
    @Timeout(180)
    void findsBookByLongTitleBehindSessionChallenge() {
        Fb2TopParser parser = parser();

        List<BookMetadata> results = parser.fetchMetadata(
                Book.builder().title("Не на своём месте, или Целитель в академии тёмных").build(),
                FetchMetadataRequest.builder()
                        .title("Не на своём месте, или Целитель в академии тёмных")
                        .author("Лика Вериор")
                        .build());

        assertThat(results).as("FB2.top results for the reported book").isNotEmpty();
        BookMetadata first = results.getFirst();
        assertThat(first.getFb2TopId())
                .isEqualTo("ne-na-svoem-meste-ili-celitely-v-akademii-temnyh-598520");
        assertThat(first.getTitle()).contains("Не на своём месте");
        assertThat(first.getAuthors()).anyMatch(author -> author.contains("Вериор"));
        assertThat(first.getPageCount()).isPositive();
    }
}
