package org.booklore.service.metadata.parser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Search-term helpers shared by the Russian metadata providers.
 */
public final class MetadataSearchUtils {

    private MetadataSearchUtils() {
    }

    /**
     * Returns search-friendly variants of an author name. Sites commonly omit
     * patronymics, so "Галина Васильевна Герасимова" is also searched as
     * "Галина Герасимова". Reversed forms ("Герасимова Галина Васильевна")
     * are covered too.
     */
    public static List<String> authorVariants(String author) {
        if (author == null || author.isBlank()) {
            return List.of();
        }
        String normalized = author.replace(',', ' ').trim().replaceAll("\\s+", " ");
        String[] tokens = normalized.split(" ");
        Set<String> variants = new LinkedHashSet<>();
        variants.add(normalized);

        if (tokens.length >= 2) {
            String first = tokens[0];
            String last = tokens[tokens.length - 1];
            if (!first.equalsIgnoreCase(last)) {
                variants.add(first + " " + last);
            }
            variants.add(last);
            variants.add(first);
        }
        if (tokens.length >= 3) {
            variants.add(tokens[0] + " " + tokens[1]);
        }

        return new ArrayList<>(variants);
    }

    /**
     * Returns raw and normalized ISBN forms. Some catalogues index only the
     * hyphenated form, others only the digit-only form.
     */
    public static List<String> isbnVariants(String isbn) {
        if (isbn == null || isbn.isBlank()) {
            return List.of();
        }
        Set<String> variants = new LinkedHashSet<>();
        variants.add(isbn.trim());
        String cleaned = isbn.replaceAll("[^0-9Xx]", "");
        if (cleaned.endsWith("x")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1) + "X";
        }
        if (!cleaned.isBlank()) {
            variants.add(cleaned);
        }
        return new ArrayList<>(variants);
    }
}
