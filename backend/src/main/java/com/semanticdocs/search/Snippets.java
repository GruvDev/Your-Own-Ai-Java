package com.semanticdocs.search;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds the short preview under each result.
 *
 * <p>Worth being precise about what this is and is not. The ranking is semantic - it came
 * from vector similarity, so a chunk can be the best match without containing a single word
 * of the query. The snippet, on the other hand, is keyword based, because a human scanning
 * results wants to see the words they typed. Using two different techniques for ranking and
 * for display is deliberate, not an inconsistency.
 */
public final class Snippets {

    private static final int WINDOW = 260;
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "a", "an", "of", "in", "on", "for", "to", "is", "are", "was", "were",
            "and", "or", "what", "how", "why", "when", "does", "do", "did", "this", "that");

    /** Returns a window of text around the first query word that appears in the chunk. */
    public static String build(String content, String query) {
        if (content == null || content.isBlank()) return "";
        String flat = content.replaceAll("\\s+", " ").trim();
        if (flat.length() <= WINDOW) return flat;

        List<String> terms = Arrays.stream(query.toLowerCase(Locale.ROOT).split("\\W+"))
                .filter(word -> word.length() > 2 && !STOP_WORDS.contains(word))
                .toList();

        String lower = flat.toLowerCase(Locale.ROOT);
        int hit = -1;
        for (String term : terms) {
            int at = lower.indexOf(term);
            if (at >= 0) {
                hit = at;
                break;
            }
        }
        if (hit < 0) {
            return flat.substring(0, WINDOW).trim() + "...";
        }

        int start = Math.max(0, hit - WINDOW / 3);
        int end = Math.min(flat.length(), start + WINDOW);
        // Do not start or end mid-word: nudge to the nearest space.
        if (start > 0) {
            int space = flat.indexOf(' ', start);
            if (space > 0 && space < start + 25) start = space + 1;
        }
        String snippet = flat.substring(start, end).trim();
        return (start > 0 ? "..." : "") + snippet + (end < flat.length() ? "..." : "");
    }

    private Snippets() {
    }
}
