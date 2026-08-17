package com.semanticdocs.search;

import com.semanticdocs.embedding.EmbeddingProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * Caches the vector for a query string in Redis.
 *
 * <p>Note carefully <b>what</b> is cached: the embedding of the query, not the search
 * results. That distinction is a security decision, not a performance one. Results are
 * filtered by who is asking, so caching them under the query text alone would eventually
 * serve one user's passages to another. The embedding of "what were the risks?" is identical
 * for everybody and carries no ownership, so it is safe to share.
 *
 * <p>It is also where the money is. Embedding a query is a call out to the model - single
 * digit milliseconds locally, more over a network - while the index search itself is a
 * fraction of a millisecond. Caching the cheap half would have been pointless.
 *
 * <p>The cache is keyed with the model name, so changing models cannot serve stale vectors
 * from the previous one.
 */
@Component
public class QueryEmbeddingCache {

    public static final String CACHE_NAME = "queryEmbeddings";

    private final CacheManager cacheManager;
    private final EmbeddingProvider embeddingProvider;

    public QueryEmbeddingCache(CacheManager cacheManager, EmbeddingProvider embeddingProvider) {
        this.cacheManager = cacheManager;
        this.embeddingProvider = embeddingProvider;
    }

    public record Result(float[] vector, boolean cached) {
    }

    public Result embed(String query) {
        String key = embeddingProvider.modelName() + "::" + query.trim().toLowerCase();
        Cache cache = cacheManager.getCache(CACHE_NAME);

        if (cache != null) {
            float[] hit = cache.get(key, float[].class);
            if (hit != null) {
                return new Result(hit, true);
            }
        }
        float[] vector = embeddingProvider.embedQuery(query);
        if (cache != null) {
            cache.put(key, vector);
        }
        return new Result(vector, false);
    }
}
