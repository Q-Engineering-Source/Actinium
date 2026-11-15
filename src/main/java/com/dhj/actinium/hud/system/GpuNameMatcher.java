package com.dhj.actinium.hud.system;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Picks the graphics card whose name belongs to the renderer that opened the game.
 *
 * <p>The renderer string a GL context reports and the card names OSHI enumerates rarely match
 * verbatim: a driver prefixes the vendor, appends the API and revision, and spells the model with
 * its own punctuation. Both names are reduced to lowercase letters and digits, and a pair matches
 * when either folded name contains the other.
 */
final class GpuNameMatcher {
    private GpuNameMatcher() {
    }

    /**
     * Resolves which of the enumerated cards the renderer reports.
     *
     * <p>A single card is always chosen, because there is no alternative to report. With several
     * cards the renderer has to match exactly one of them; no match and more than one match both
     * leave the choice open.
     *
     * @param rendererName renderer string the GL context reports
     * @param cardNames names of the cards the sensor library enumerates, in enumeration order
     * @return index of the only matching card, or empty when the renderer cannot pick one
     */
    static OptionalInt match(String rendererName, List<String> cardNames) {
        Objects.requireNonNull(rendererName, "rendererName");
        Objects.requireNonNull(cardNames, "cardNames");
        if (cardNames.isEmpty()) {
            return OptionalInt.empty();
        }
        if (cardNames.size() == 1) {
            return OptionalInt.of(0);
        }
        String foldedRenderer = fold(rendererName);
        if (foldedRenderer.isEmpty()) {
            return OptionalInt.empty();
        }
        int matchedIndex = -1;
        for (int index = 0; index < cardNames.size(); index++) {
            String foldedCard = fold(cardNames.get(index));
            if (!containsEitherWay(foldedRenderer, foldedCard)) {
                continue;
            }
            if (matchedIndex >= 0) {
                return OptionalInt.empty();
            }
            matchedIndex = index;
        }
        return matchedIndex < 0 ? OptionalInt.empty() : OptionalInt.of(matchedIndex);
    }

    private static boolean containsEitherWay(String foldedRenderer, String foldedCard) {
        return !foldedCard.isEmpty()
            && (foldedRenderer.contains(foldedCard) || foldedCard.contains(foldedRenderer));
    }

    private static String fold(String name) {
        StringBuilder folded = new StringBuilder(name.length());
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                folded.append(character);
            }
        }
        return folded.toString().toLowerCase(Locale.ROOT);
    }
}
