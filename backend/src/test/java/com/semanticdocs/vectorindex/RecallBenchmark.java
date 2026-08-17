package com.semanticdocs.vectorindex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Measures HNSW against brute force and prints a table you can paste into the README.
 *
 * <p>Runs with no build tool and no dependencies:
 * <pre>
 *   java -m jdk.compiler/com.sun.tools.javac.Main -d /tmp/bench \
 *        backend/src/main/java/com/semanticdocs/vectorindex/*.java \
 *        backend/src/test/java/com/semanticdocs/vectorindex/RecallBenchmark.java
 *   java -cp /tmp/bench com.semanticdocs.vectorindex.RecallBenchmark [vectors] [dim] [queries]
 * </pre>
 *
 * <p>Recall@k is the fraction of the true k nearest neighbours that HNSW returned.
 * Brute force is exact by definition, so it is the ground truth.
 *
 * <p>Two datasets are measured on purpose. Uniform random vectors in high dimensions are the
 * hostile case: every point is roughly equidistant from every other, so "nearest" barely means
 * anything and every ANN algorithm struggles. Clustered vectors mimic real text embeddings,
 * which sit on a low-dimensional manifold. The gap between the two tables is the single most
 * useful thing this benchmark teaches: recall is a property of your data, not just your code.
 */
public final class RecallBenchmark {

    private static final int K = 10;
    private static final int[] EF_VALUES = {16, 32, 64, 128};

    public static void main(String[] args) throws Exception {
        int vectorCount = intArg(args, 0, 20_000);
        int dim = intArg(args, 1, 128);
        int queryCount = intArg(args, 2, 200);

        System.out.println("SemanticDocs vector index benchmark");
        System.out.printf("vectors=%d  dim=%d  queries=%d  k=%d  metric=COSINE  M=16  efConstruction=200%n",
                vectorCount, dim, queryCount, K);

        Random random = new Random(42);
        HnswIndex clusteredIndex = runDataset("CLUSTERED (realistic - like text embeddings)",
                clusteredVectors(vectorCount, dim, 50, random),
                clusteredVectors(queryCount, dim, 50, random), dim);
        runDataset("UNIFORM RANDOM (worst case - no structure to exploit)",
                randomVectors(vectorCount, dim, random),
                randomVectors(queryCount, dim, random), dim);

        verifyDeleteAndPersistence(clusteredIndex, clusteredVectors(1, dim, 50, random)[0], dim);
    }

    private static HnswIndex runDataset(String title, float[][] data, float[][] queries, int dim)
            throws Exception {
        System.out.println();
        System.out.println(title);
        System.out.println("=".repeat(title.length()));

        BruteForceIndex brute = new BruteForceIndex(dim, DistanceMetric.COSINE);
        long start = System.nanoTime();
        for (int i = 0; i < data.length; i++) {
            brute.add(i, data[i]);
        }
        long bruteBuildMs = msSince(start);

        List<Set<Long>> truth = new ArrayList<>(queries.length);
        long[] bruteLatencies = new long[queries.length];
        for (int q = 0; q < queries.length; q++) {
            long t = System.nanoTime();
            List<SearchHit> hits = brute.search(queries[q], K, 0);
            bruteLatencies[q] = System.nanoTime() - t;
            Set<Long> ids = new HashSet<>();
            for (SearchHit hit : hits) {
                ids.add(hit.externalId());
            }
            truth.add(ids);
        }
        double bruteP50 = percentileMs(bruteLatencies, 50);

        System.out.printf("%-22s %10s %9s %9s %9s %9s%n",
                "index", "build(ms)", "recall@10", "p50(ms)", "p95(ms)", "speedup");
        System.out.println("-".repeat(74));
        System.out.printf("%-22s %10d %9s %9.3f %9.3f %9s%n",
                "BruteForce (exact)", bruteBuildMs, "1.000",
                bruteP50, percentileMs(bruteLatencies, 95), "1.0x");

        HnswIndex hnsw = new HnswIndex(dim, DistanceMetric.COSINE, 16, 200, new Random(7));
        start = System.nanoTime();
        for (int i = 0; i < data.length; i++) {
            hnsw.add(i, data[i]);
        }
        long hnswBuildMs = msSince(start);

        for (int ef : EF_VALUES) {
            long[] latencies = new long[queries.length];
            double recallSum = 0;
            for (int q = 0; q < queries.length; q++) {
                long t = System.nanoTime();
                List<SearchHit> hits = hnsw.search(queries[q], K, ef);
                latencies[q] = System.nanoTime() - t;
                int found = 0;
                for (SearchHit hit : hits) {
                    if (truth.get(q).contains(hit.externalId())) found++;
                }
                recallSum += (double) found / K;
            }
            double p50 = percentileMs(latencies, 50);
            System.out.printf("%-22s %10s %9.3f %9.3f %9.3f %8.1fx%n",
                    "HNSW ef=" + ef,
                    ef == EF_VALUES[0] ? String.valueOf(hnswBuildMs) : "",
                    recallSum / queries.length,
                    p50,
                    percentileMs(latencies, 95),
                    p50 > 0 ? bruteP50 / p50 : Double.NaN);
        }

        HnswIndex.IndexStats stats = hnsw.stats();
        System.out.printf("graph: %d nodes, %d edges, top layer %d, ~%.1f MB in memory%n",
                stats.nodeCount(), stats.edgeCount(), stats.maxLevel(),
                stats.approximateBytes() / 1024.0 / 1024.0);
        return hnsw;
    }

    /** Sanity checks that mirror the assertions in HnswIndexTest. */
    private static void verifyDeleteAndPersistence(HnswIndex hnsw, float[] query, int dim)
            throws Exception {
        System.out.println();
        System.out.println("CORRECTNESS CHECKS");
        System.out.println("==================");

        List<SearchHit> before = hnsw.search(query, K, 64);
        long victim = before.get(0).externalId();
        hnsw.remove(victim);
        List<SearchHit> after = hnsw.search(query, K, 64);
        boolean leaked = after.stream().anyMatch(h -> h.externalId() == victim);
        System.out.printf("delete    : tombstoned %d, still returned? %b, result count %d%n",
                victim, leaked, after.size());

        Path file = Files.createTempFile("semanticdocs-index", ".bin");
        hnsw.save(file);
        HnswIndex reloaded = HnswIndex.load(file);
        List<SearchHit> afterReload = reloaded.search(query, K, 64);
        boolean identical = afterReload.size() == after.size();
        for (int i = 0; identical && i < after.size(); i++) {
            identical = afterReload.get(i).externalId() == after.get(i).externalId();
        }
        System.out.printf("persist   : %.2f MB on disk, identical results after reload? %b%n",
                Files.size(file) / 1024.0 / 1024.0, identical);
        Files.deleteIfExists(file);

        float[] q = query.clone();
        DistanceMetric.normalise(q);
        System.out.printf("normalise : |v| after normalisation = %.6f%n", magnitude(q));
    }

    /** Vectors drawn from a handful of Gaussian blobs, the way real embeddings behave. */
    private static float[][] clusteredVectors(int count, int dim, int clusters, Random random) {
        float[][] centres = new float[clusters][dim];
        Random centreRandom = new Random(1234); // same centres for data and queries
        for (int c = 0; c < clusters; c++) {
            for (int d = 0; d < dim; d++) {
                centres[c][d] = (float) centreRandom.nextGaussian();
            }
        }
        float[][] out = new float[count][dim];
        for (int i = 0; i < count; i++) {
            float[] centre = centres[random.nextInt(clusters)];
            for (int d = 0; d < dim; d++) {
                out[i][d] = centre[d] + (float) random.nextGaussian() * 0.35f;
            }
        }
        return out;
    }

    private static float[][] randomVectors(int count, int dim, Random random) {
        float[][] out = new float[count][dim];
        for (int i = 0; i < count; i++) {
            for (int d = 0; d < dim; d++) {
                out[i][d] = (float) random.nextGaussian();
            }
        }
        return out;
    }

    private static double magnitude(float[] v) {
        double sum = 0;
        for (float x : v) sum += (double) x * x;
        return Math.sqrt(sum);
    }

    private static double percentileMs(long[] nanos, int percentile) {
        long[] copy = nanos.clone();
        Arrays.sort(copy);
        int idx = (int) Math.ceil(percentile / 100.0 * copy.length) - 1;
        return copy[Math.max(0, Math.min(idx, copy.length - 1))] / 1_000_000.0;
    }

    private static long msSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static int intArg(String[] args, int index, int fallback) {
        return args.length > index ? Integer.parseInt(args[index]) : fallback;
    }

    private RecallBenchmark() {
    }
}
