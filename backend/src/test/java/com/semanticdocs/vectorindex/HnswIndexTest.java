package com.semanticdocs.vectorindex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tests that matter for this project.
 *
 * <p>An approximate index cannot be tested with "assertEquals(expected, actual)" - by design
 * it is allowed to be wrong sometimes. So we test it the way the research does: measure recall
 * against exact brute-force results and assert it clears a threshold.
 */
class HnswIndexTest {

    private static final int DIM = 64;
    private static final int COUNT = 3000;
    private static final int K = 10;

    @Test
    @DisplayName("recall against brute force stays above 90% on clustered data")
    void recallIsHighOnRealisticData() {
        Random random = new Random(11);
        float[][] data = clustered(COUNT, DIM, 30, random);
        float[][] queries = clustered(50, DIM, 30, random);

        BruteForceIndex exact = new BruteForceIndex(DIM, DistanceMetric.COSINE);
        HnswIndex approximate = new HnswIndex(DIM, DistanceMetric.COSINE, 16, 200, new Random(3));
        for (int i = 0; i < data.length; i++) {
            exact.add(i, data[i]);
            approximate.add(i, data[i]);
        }

        double recallSum = 0;
        for (float[] query : queries) {
            Set<Long> truth = ids(exact.search(query, K, 0));
            Set<Long> found = ids(approximate.search(query, K, 64));
            found.retainAll(truth);
            recallSum += (double) found.size() / K;
        }
        double recall = recallSum / queries.length;
        assertTrue(recall > 0.90, "recall@10 was only " + recall);
    }

    @Test
    @DisplayName("higher ef never lowers recall")
    void recallImprovesWithEf() {
        Random random = new Random(5);
        float[][] data = clustered(1500, DIM, 20, random);
        float[][] queries = clustered(30, DIM, 20, random);

        BruteForceIndex exact = new BruteForceIndex(DIM, DistanceMetric.COSINE);
        HnswIndex index = new HnswIndex(DIM, DistanceMetric.COSINE, 16, 200, new Random(9));
        for (int i = 0; i < data.length; i++) {
            exact.add(i, data[i]);
            index.add(i, data[i]);
        }
        double low = averageRecall(exact, index, queries, 8);
        double high = averageRecall(exact, index, queries, 128);
        assertTrue(high >= low, "ef=128 recall " + high + " was below ef=8 recall " + low);
    }

    @Test
    @DisplayName("a removed vector never comes back in results")
    void removedVectorsAreHidden() {
        HnswIndex index = build(500);
        float[] query = index.search(new float[DIM], 1, 32).isEmpty()
                ? new float[DIM] : new float[DIM];
        query[0] = 1f;

        List<SearchHit> before = index.search(query, K, 64);
        long victim = before.get(0).externalId();
        assertTrue(index.remove(victim));

        List<SearchHit> after = index.search(query, K, 64);
        assertFalse(after.stream().anyMatch(hit -> hit.externalId() == victim));
        assertEquals(499, index.size());
    }

    @Test
    @DisplayName("a saved index reloads and returns the same results")
    void saveAndLoadRoundTrip() throws Exception {
        HnswIndex index = build(800);
        float[] query = new float[DIM];
        query[3] = 1f;
        List<SearchHit> before = index.search(query, K, 64);

        Path file = Files.createTempFile("index", ".bin");
        try {
            index.save(file);
            HnswIndex reloaded = HnswIndex.load(file);
            List<SearchHit> after = reloaded.search(query, K, 64);

            assertEquals(before.size(), after.size());
            for (int i = 0; i < before.size(); i++) {
                assertEquals(before.get(i).externalId(), after.get(i).externalId());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @DisplayName("concurrent searches and inserts do not corrupt the graph")
    void survivesConcurrentAccess() throws Exception {
        HnswIndex index = build(500);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        Random random = new Random(77);

        for (int t = 0; t < 8; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 100; i++) {
                        if (threadId % 4 == 0) {
                            index.add(10_000L + threadId * 100L + i, randomVector(random));
                        } else {
                            List<SearchHit> hits = index.search(randomVector(random), K, 32);
                            if (hits.isEmpty()) failures.incrementAndGet();
                        }
                    }
                } catch (Exception ex) {
                    failures.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "threads did not finish");
        assertEquals(0, failures.get(), "concurrent access produced failures");
    }

    // ------------------------------------------------------------------ helpers

    private double averageRecall(VectorIndex exact, VectorIndex approximate,
                                 float[][] queries, int ef) {
        double sum = 0;
        for (float[] query : queries) {
            Set<Long> truth = ids(exact.search(query, K, 0));
            Set<Long> found = ids(approximate.search(query, K, ef));
            found.retainAll(truth);
            sum += (double) found.size() / K;
        }
        return sum / queries.length;
    }

    private HnswIndex build(int count) {
        Random random = new Random(21);
        HnswIndex index = new HnswIndex(DIM, DistanceMetric.COSINE, 16, 200, new Random(31));
        for (int i = 0; i < count; i++) {
            index.add(i, randomVector(random));
        }
        return index;
    }

    private static Set<Long> ids(List<SearchHit> hits) {
        Set<Long> ids = new HashSet<>();
        for (SearchHit hit : hits) {
            ids.add(hit.externalId());
        }
        return ids;
    }

    private static float[] randomVector(Random random) {
        float[] vector = new float[DIM];
        for (int i = 0; i < DIM; i++) {
            vector[i] = (float) random.nextGaussian();
        }
        return vector;
    }

    private static float[][] clustered(int count, int dim, int clusters, Random random) {
        Random centreRandom = new Random(999);
        float[][] centres = new float[clusters][dim];
        for (int c = 0; c < clusters; c++) {
            for (int d = 0; d < dim; d++) {
                centres[c][d] = (float) centreRandom.nextGaussian();
            }
        }
        List<float[]> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            float[] centre = centres[random.nextInt(clusters)];
            float[] vector = new float[dim];
            for (int d = 0; d < dim; d++) {
                vector[d] = centre[d] + (float) random.nextGaussian() * 0.3f;
            }
            out.add(vector);
        }
        return out.toArray(new float[0][]);
    }
}
