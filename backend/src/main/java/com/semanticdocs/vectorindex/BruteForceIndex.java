package com.semanticdocs.vectorindex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Compares the query against every single vector. O(n * d) per query.
 *
 * <p>Nobody ships this at scale, but it is essential: it gives the exact answer,
 * so it is the ground truth we measure HNSW's recall against. Keeping it in the
 * codebase is what turns "I implemented HNSW" into "I proved my HNSW is correct".
 */
public class BruteForceIndex implements VectorIndex {

    private final int dim;
    private final DistanceMetric metric;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private final List<float[]> vectors = new ArrayList<>();
    private final List<Long> externalIds = new ArrayList<>();
    private final List<Boolean> alive = new ArrayList<>();
    private int liveCount = 0;

    public BruteForceIndex(int dim, DistanceMetric metric) {
        this.dim = dim;
        this.metric = metric;
    }

    @Override
    public void add(long externalId, float[] vector) {
        if (vector.length != dim) {
            throw new IllegalArgumentException(
                    "Expected dimension " + dim + " but got " + vector.length);
        }
        float[] copy = vector.clone();
        if (metric.requiresNormalisation()) {
            DistanceMetric.normalise(copy);
        }
        lock.writeLock().lock();
        try {
            vectors.add(copy);
            externalIds.add(externalId);
            alive.add(Boolean.TRUE);
            liveCount++;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean remove(long externalId) {
        lock.writeLock().lock();
        try {
            for (int i = 0; i < externalIds.size(); i++) {
                if (externalIds.get(i) == externalId && alive.get(i)) {
                    alive.set(i, Boolean.FALSE);
                    liveCount--;
                    return true;
                }
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<SearchHit> search(float[] query, int k, int ef) {
        float[] q = query.clone();
        if (metric.requiresNormalisation()) {
            DistanceMetric.normalise(q);
        }
        // A max-heap of size k: the worst element sits on top so we can evict it cheaply.
        PriorityQueue<SearchHit> worstOnTop =
                new PriorityQueue<>(Comparator.comparingDouble(SearchHit::distance).reversed());

        lock.readLock().lock();
        try {
            for (int i = 0; i < vectors.size(); i++) {
                if (!alive.get(i)) continue;
                float d = metric.distance(q, vectors.get(i));
                if (worstOnTop.size() < k) {
                    worstOnTop.add(SearchHit.of(externalIds.get(i), d, metric));
                } else if (d < worstOnTop.peek().distance()) {
                    worstOnTop.poll();
                    worstOnTop.add(SearchHit.of(externalIds.get(i), d, metric));
                }
            }
        } finally {
            lock.readLock().unlock();
        }

        List<SearchHit> out = new ArrayList<>(worstOnTop);
        out.sort(null); // SearchHit is Comparable: best first
        return out;
    }

    @Override
    public int size() {
        lock.readLock().lock();
        try {
            return liveCount;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int dimension() {
        return dim;
    }

    @Override
    public DistanceMetric metric() {
        return metric;
    }

    @Override
    public String name() {
        return "BruteForce";
    }
}
