package com.semanticdocs.document;

import com.semanticdocs.common.ApiExceptions;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Component;

/**
 * Turns any supported file into plain text.
 *
 * <p>Apache Tika sniffs the real type from the file's magic bytes rather than trusting the
 * extension, and handles PDF, DOCX, PPTX, HTML, EPUB and plain text through one API. A
 * PDF-only project could use PDFBox directly and ship a much smaller jar - that is the
 * trade-off being made here in favour of accepting more formats.
 */
@Component
public class TextExtractor {

    /** 20 million characters is far past anything sane and stops a zip bomb eating the heap. */
    private static final int MAX_CHARS = 20_000_000;

    private final Tika tika = new Tika();

    public TextExtractor() {
        tika.setMaxStringLength(MAX_CHARS);
    }

    public String extract(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            String text = tika.parseToString(in);
            String cleaned = normalise(text);
            if (cleaned.isBlank()) {
                throw new ApiExceptions.BadRequestException(
                        "No readable text found. If this is a scanned PDF it needs OCR first.");
            }
            return cleaned;
        } catch (IOException | TikaException ex) {
            throw new ApiExceptions.UpstreamException("Could not read the file", ex);
        }
    }

    /**
     * Collapses the whitespace mess that PDF extraction produces: stray carriage returns,
     * runs of blank lines, and lines of spaces. Cleaner text means cleaner embeddings.
     */
    private String normalise(String raw) {
        return raw.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}
