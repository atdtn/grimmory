package org.booklore.model.enums;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum MetadataProvider {
    OpenLibrary,
    Amazon,
    GoodReads,
    Google,
    Hardcover,
    Comicvine,
    Douban,
    Lubimyczytac,
    Litres,
    @JsonProperty("FantasyWorlds")
    FANTASY_WORLDS,
    @JsonProperty("Litnet")
    LITNET,
    @JsonProperty("Labirint")
    LABIRINT,
    Ranobedb,
    Audible,
    AppleBooks
}
