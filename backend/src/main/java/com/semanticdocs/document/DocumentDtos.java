package com.semanticdocs.document;

import java.time.Instant;

public final class DocumentDtos {

    public record DocumentResponse(
            Long id,
            String filename,
            String contentType,
            long sizeBytes,
            String status,
            String errorMessage,
            int chunkCount,
            Instant createdAt,
            Instant indexedAt) {

        public static DocumentResponse from(Document document) {
            return new DocumentResponse(
                    document.getId(),
                    document.getFilename(),
                    document.getContentType(),
                    document.getSizeBytes(),
                    document.getStatus().name(),
                    document.getErrorMessage(),
                    document.getChunkCount(),
                    document.getCreatedAt(),
                    document.getIndexedAt());
        }
    }

    private DocumentDtos() {
    }
}
