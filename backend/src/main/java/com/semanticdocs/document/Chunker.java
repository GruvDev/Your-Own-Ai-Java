package com.semanticdocs.document;

import com.semanticdocs.config.AppProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Splits text into overlapping windows.
 *
 * <p>Two parameters decide everything. <b>Size</b> trades precision against context: chunks
 * that are too small lose the surrounding sentences that make a passage meaningful, chunks
 * that are too big dilute the embedding so that one relevant line is averaged in with four
 * irrelevant paragraphs. <b>Overlap</b> exists because a fixed cut lands in the middle of an
 * idea roughly whenever it feels like it; repeating the tail of the previous chunk means an
 * answer that straddles a boundary still appears whole somewhere.
 *
 * <p>We cut on paragraph breaks when one is nearby, then sentence ends, then whitespace, and
 * only slice mid-word as a last resort. A boundary that respects the writing is worth more
 * than a boundary that respects the character count.
 */
@Component
public class Chunker {

    private final int targetSize;
    private final int overlap;

    public Chunker(AppProperties properties) {
        this.targetSize = properties.getChunking().getSize();
        this.overlap = properties.getChunking().getOverlap();
        if (overlap >= targetSize) {
            throw new IllegalStateException("Chunk overlap must be smaller than chunk size");
        }
    }

    /** A slice of the original text plus where it came from. */
    public record TextChunk(String content, int start, int end) {
    }

    public List<TextChunk> split(String text) {
        List<TextChunk> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;

        int position = 0;
        int length = text.length();

        while (position < length) {
            int hardEnd = Math.min(position + targetSize, length);
            int end = (hardEnd == length) ? length : findBoundary(text, position, hardEnd);

            String content = text.substring(position, end).trim();
            if (!content.isEmpty()) {
                chunks.add(new TextChunk(content, position, end));
            }
            if (end >= length) break;

            // Step forward by size minus overlap, never backwards (that would loop forever).
            int next = end - overlap;
            position = Math.max(next, position + 1);
        }
        return chunks;
    }

    /**
     * Looks backwards from the hard limit for a natural break, but not more than 30% of the
     * chunk - beyond that we would produce wildly uneven chunks just to find a full stop.
     */
    private int findBoundary(String text, int start, int hardEnd) {
        int floor = start + (int) (targetSize * 0.7);

        int paragraph = text.lastIndexOf("\n\n", hardEnd);
        if (paragraph > floor) return paragraph + 2;

        for (String terminator : new String[]{". ", ".\n", "! ", "? "}) {
            int sentence = text.lastIndexOf(terminator, hardEnd);
            if (sentence > floor) return sentence + terminator.length();
        }
        int space = text.lastIndexOf(' ', hardEnd);
        if (space > floor) return space + 1;

        return hardEnd;
    }
}
