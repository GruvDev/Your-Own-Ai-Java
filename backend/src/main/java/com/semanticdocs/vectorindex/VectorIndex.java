package com.semanticdocs.vectorindex;

import java.util.List;

/**
 * The contract every index implementation obeys.
 *
 * <p>Two implementations exist on purpose: {@link BruteForceIndex} is the slow but
 * always-correct reference, and {@link HnswIndex} is the fast approximate one. The
 * recall test measures HNSW against brute force, which is how we prove HNSW works.
 */
public interface VectorIndex {

    /** Adds one vector. externalId is our own id (a chunk id). */
    void add(long externalId, float[] vector);

    /** Marks a vector as deleted. It stays in memory but is filtered out of results. */
    boolean remove(long externalId);

    /**
     * Returns the k closest vectors, best first.
     *
     * @param ef search breadth - higher means better recall and slower queries.
     *           Ignored by brute force, which is always exact.
     */
    List<SearchHit> search(float[] query, int k, int ef);

    /** Number of live (non-deleted) vectors. */
    int size();

    /** Vector length this index was built for. */
    int dimension();

    DistanceMetric metric();

    /** Human-readable name used in benchmark output. */
    String name();
}
