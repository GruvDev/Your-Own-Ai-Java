package com.semanticdocs.search;

import com.semanticdocs.auth.CurrentUser;
import com.semanticdocs.document.Chunk;
import com.semanticdocs.document.ChunkRepository;
import com.semanticdocs.vectorindex.SearchHit;
import com.semanticdocs.vectorindex.VectorIndexService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Query text in, ranked passages out.
 *
 * <p>The flow is short but every step matters:
 * <ol>
 *   <li>Embed the query with the <i>same</i> model used for the documents. A vector from a
 *       different model is not comparable - the numbers would still produce a ranking, just
 *       a meaningless one.</li>
 *   <li>Ask the index for candidates. We over-fetch, because filtering happens next.</li>
 *   <li>Load the chunk rows, enforcing ownership and any document filter.</li>
 *   <li>Rebuild the ranking, since a Postgres IN query returns rows in whatever order it
 *       likes and the ranking is the entire product.</li>
 * </ol>
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final QueryEmbeddingCache queryEmbeddingCache;
    private final VectorIndexService indexService;
    private final ChunkRepository chunkRepository;
    private final CurrentUser currentUser;
    private final float minScore;

    public SearchService(QueryEmbeddingCache queryEmbeddingCache,
                         VectorIndexService indexService,
                         ChunkRepository chunkRepository,
                         CurrentUser currentUser,
                         com.semanticdocs.config.AppProperties properties) {
        this.queryEmbeddingCache = queryEmbeddingCache;
        this.indexService = indexService;
        this.chunkRepository = chunkRepository;
        this.currentUser = currentUser;
        this.minScore = properties.getSearch().getMinScore();
    }

    @Transactional(readOnly = true)
    public SearchDtos.SearchResponse search(SearchDtos.SearchRequest request) {
        long start = System.nanoTime();
        Long userId = currentUser.require().getId();
        int topK = request.topKOrDefault();

        QueryEmbeddingCache.Result embedding = queryEmbeddingCache.embed(request.query());
        float[] queryVector = embedding.vector();

        // Over-fetch so that discarding other users' chunks (and non-matching documents)
        // still leaves enough results to fill the page.
        int candidateCount = Math.min(topK * 5 + 20, 500);
        List<SearchHit> hits = request.ef() == null
                ? indexService.search(queryVector, candidateCount)
                : indexService.search(queryVector, candidateCount, request.ef());

        if (hits.isEmpty()) {
            return new SearchDtos.SearchResponse(
                    request.query(), 0, millisSince(start), embedding.cached(), List.of());
        }

        List<Long> chunkIds = new ArrayList<>(hits.size());
        for (SearchHit hit : hits) {
            chunkIds.add(hit.externalId());
        }

        // One query with a join fetch, not one query per result. See ChunkRepository.
        // Then into a map, so resolving each hit is O(1) instead of scanning the list.
        Map<Long, Chunk> chunksById = chunkRepository.findAllByIdWithDocument(chunkIds)
                .stream().collect(Collectors.toMap(Chunk::getId, Function.identity(),
                        (a, b) -> a, HashMap::new));

        List<SearchDtos.SearchResultItem> results = new ArrayList<>(topK);
        for (SearchHit hit : hits) {
            if (results.size() == topK) break;

            // A relevance floor, applied before anything else. The index returns its k
            // nearest neighbours unconditionally, and "nearest" is not "relevant" - on a
            // query the corpus simply does not cover, the nearest thing is still weakly
            // related noise. Without this floor every question gets a full page of results
            // and the model is handed passages that have nothing to do with it.
            if (hit.score() < minScore) break; // hits are ordered, so nothing after this passes

            Chunk chunk = chunksById.get(hit.externalId());
            if (chunk == null) continue;

            // Authorisation is applied here, after ranking. The index itself holds every
            // user's vectors, so this filter is what keeps tenants apart.
            if (!chunk.getDocument().getUser().getId().equals(userId)) continue;
            if (request.documentId() != null
                    && !chunk.getDocument().getId().equals(request.documentId())) continue;

            results.add(new SearchDtos.SearchResultItem(
                    chunk.getId(),
                    chunk.getDocument().getId(),
                    chunk.getDocument().getFilename(),
                    chunk.getChunkIndex(),
                    hit.score(),
                    Snippets.build(chunk.getContent(), request.query()),
                    chunk.getContent()));
        }

        long took = millisSince(start);
        log.debug("Query '{}' matched {} chunks in {} ms", request.query(), results.size(), took);
        return new SearchDtos.SearchResponse(
                request.query(), results.size(), took, embedding.cached(), results);
    }

    private long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
