package org.booklore.model.dto.settings;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class MetadataProviderSpecificFields {
    private Boolean openlibraryId;
    private Boolean asin;
    private Boolean amazonRating;
    private Boolean amazonReviewCount;
    private Boolean googleId;
    private Boolean goodreadsId;
    private Boolean goodreadsRating;
    private Boolean goodreadsReviewCount;
    private Boolean hardcoverId;
    private Boolean hardcoverBookId;
    private Boolean hardcoverRating;
    private Boolean hardcoverReviewCount;
    private Boolean comicvineId;
    private Boolean lubimyczytacId;
    private Boolean lubimyczytacRating;
    private Boolean litresId = true;
    private Boolean litresRating = true;
    private Boolean litresReviewCount = true;
    private Boolean fantasyWorldsId = true;
    private Boolean fantasyWorldsRating = true;
    private Boolean fantasyWorldsReviewCount = true;
    private Boolean ranobedbId;
    private Boolean ranobedbRating;
    private Boolean audibleId;
    private Boolean audibleRating;
    private Boolean audibleReviewCount;
    private Boolean applebooksId;
    private Boolean applebooksRating;
    private Boolean applebooksReviewCount;
    private Boolean doubanId;
    private Boolean doubanRating;
    private Boolean doubanReviewCount;
}
