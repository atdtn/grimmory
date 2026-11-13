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
     * Matches a title/author while tolerating Russian inflection, but requires
     * the query words to appear in the same order and close together. This
     * prevents titles such as "Дальняя дорога, старая любовь" from matching
     * the query "Старые дороги".
     */
    public static boolean matchesText(String haystack, String needle) {
        String[] haystackTokens = normalizeText(haystack).split(" ");
        String[] needleTokens = normalizeText(needle).split(" ");
        if (haystackTokens.length == 0 || haystackTokens[0].isBlank()
                || needleTokens.length == 0 || needleTokens[0].isBlank()) {
            return false;
        }

        int haystackIndex = 0;
        int firstMatch = -1;
        int lastMatch = -1;
        for (String needleToken : needleTokens) {
            if (needleToken.isBlank()) {
                continue;
            }
            boolean found = false;
            for (int i = haystackIndex; i < haystackTokens.length; i++) {
                if (tokenMatches(haystackTokens[i], needleToken)) {
                    if (firstMatch < 0) {
                        firstMatch = i;
                    }
                    lastMatch = i;
                    haystackIndex = i + 1;
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return lastMatch - firstMatch + 1 <= needleTokens.length + 2;
    }

    private static boolean tokenMatches(String haystackToken, String needleToken) {
        if (haystackToken.isBlank() || needleToken.isBlank()) {
            return false;
        }
        int stemLength = needleToken.length() <= 3
                ? needleToken.length()
                : Math.min(4, needleToken.length() - 1);
        return haystackToken.contains(needleToken.substring(0, stemLength));
    }

    public static String normalizeText(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
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
