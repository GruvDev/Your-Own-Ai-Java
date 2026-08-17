package com.semanticdocs.search;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class SearchDtos {

    public record SearchRequest(
            @NotBlank @Size(max = 1000) String query,
            @Min(1) @Max(50) Integer topK,
            Long documentId,      // optional: restrict the search to one document
            Integer ef) {         // optional: override search breadth, for the demo page

        public int topKOrDefault() {
            return topK == null ? 10 : topK;
        }
    }

    public record SearchResultItem(
            Long chunkId,
            Long documentId,
            String filename,
            int chunkIndex,
            float score,
            String snippet,
            String content) {
    }

    public record SearchResponse(
            String query,
            int resultCount,
            long tookMillis,
            boolean cached,
            List<SearchResultItem> results) {
    }

    private SearchDtos() {
    }
}
