package com.semanticdocs.vectorindex;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Hierarchical Navigable Small World index (Malkov and Yashunin, 2016).
 *
 * <p>The idea in one paragraph: build a graph where every vector is a node connected to
 * a few of its near neighbours. To search, start somewhere and keep walking to whichever
 * neighbour is closer to the query, like following signposts. A flat graph makes you walk
 * a long way, so we stack layers: the top layer is sparse and lets you cross the whole
 * dataset in a few hops (an express highway), and each layer down is denser until layer 0
 * holds every vector (local streets). We descend layer by layer, so we arrive near the
 * answer before doing any fine-grained work.
 *
 * <p>Approximate means it can miss a true neighbour occasionally. That is the trade we make
 * for speed, and {@code RecallBenchmark} measures exactly how often it happens.
 *
 * <p>Thread safety: a {@link ReentrantReadWriteLock} lets many searches run at once but
 * gives an insert exclusive access. Reads dominate in this application, which is why a
 * read-write lock beats a plain {@code synchronized} block here.
 */
public class HnswIndex implements VectorIndex {

    // ---------------------------------------------------------------- tuning knobs

    /** Max neighbours per node on layers above 0. Higher = better recall, more memory. */
    private final int m;

    /** Max neighbours on layer 0. The paper suggests 2*M. */
    private final int maxM0;

    /** How many candidates to keep while building. Higher = better graph, slower build. */
    private final int efConstruction;

    /** 1/ln(M). Controls how quickly layers thin out as you go up. */
    private final double levelMultiplier;

    private final int dim;
    private final DistanceMetric metric;

    // ---------------------------------------------------------------- state

    private final List<Node> nodes = new ArrayList<>();
    private final Map<Long, Integer> internalIdByExternalId = new HashMap<>();
    private final Set<Integer> deleted = new HashSet<>();

    private int entryPoint = -1;
    private int maxLevel = -1;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Random random;

    public HnswIndex(int dim, DistanceMetric metric, int m, int efConstruction) {
        this(dim, metric, m, efConstruction, new Random());
    }

    /** Seeded constructor so tests and benchmarks are reproducible. */
    public HnswIndex(int dim, DistanceMetric metric, int m, int efConstruction, Random random) {
        if (dim <= 0) throw new IllegalArgumentException("dim must be positive");
        if (m < 2) throw new IllegalArgumentException("M must be at least 2");
        this.dim = dim;
        this.metric = metric;
        this.m = m;
        this.maxM0 = m * 2;
        this.efConstruction = Math.max(efConstruction, m);
        this.levelMultiplier = 1.0 / Math.log(m);
        this.random = random;
    }

    /** One vector plus its neighbour lists, one list per layer it lives on. */
    private static final class Node {
        final int id;
        final long externalId;
        final float[] vector;
        final List<List<Integer>> connections; // connections.get(layer) -> neighbour ids

        Node(int id, long externalId, float[] vector, int level) {
            this.id = id;
            this.externalId = externalId;
            this.vector = vector;
            this.connections = new ArrayList<>(level + 1);
            for (int i = 0; i <= level; i++) {
                this.connections.add(new ArrayList<>());
            }
        }

        int level() {
            return connections.size() - 1;
        }
    }

    /** A node id paired with its distance to whatever we are currently searching for. */
    private record Candidate(int id, float distance) implements Comparable<Candidate> {
        @Override
        public int compareTo(Candidate o) {
            return Float.compare(this.distance, o.distance); // ascending: closest first
        }
    }

    // ---------------------------------------------------------------- insert

    @Override
    public void add(long externalId, float[] vector) {
        if (vector.length != dim) {
            throw new IllegalArgumentException(
                    "Expected dimension " + dim + " but got " + vector.length);
        }
        float[] vec = vector.clone();
        if (metric.requiresNormalisation()) {
            DistanceMetric.normalise(vec);
        }

        lock.writeLock().lock();
        try {
            if (internalIdByExternalId.containsKey(externalId)) {
                throw new IllegalStateException("Vector already indexed: " + externalId);
            }

            int level = randomLevel();
            int id = nodes.size();
            Node node = new Node(id, externalId, vec, level);
            nodes.add(node);
            internalIdByExternalId.put(externalId, id);

            // First vector ever: it becomes the entry point and there is nothing to link to.
            if (entryPoint == -1) {
                entryPoint = id;
                maxLevel = level;
                return;
            }

            // Phase 1: from the top, greedily walk downhill until we reach the new node's
            // top layer. We only need one good starting point, so ef = 1 here.
            int current = entryPoint;
            float currentDist = distance(vec, nodes.get(current).vector);
            for (int layer = maxLevel; layer > level; layer--) {
                boolean improved = true;
                while (improved) {
                    improved = false;
                    for (int neighbour : connectionsAt(current, layer)) {
                        float d = distance(vec, nodes.get(neighbour).vector);
                        if (d < currentDist) {
                            currentDist = d;
                            current = neighbour;
                            improved = true;
                        }
                    }
                }
            }

            // Phase 2: on every layer the new node belongs to, find good neighbours and link.
            List<Integer> entryPoints = new ArrayList<>();
            entryPoints.add(current);

            for (int layer = Math.min(maxLevel, level); layer >= 0; layer--) {
                List<Candidate> candidates =
                        sortedAscending(searchLayer(vec, entryPoints, efConstruction, layer));

                List<Integer> neighbours = selectNeighbours(vec, candidates, m);
                node.connections.get(layer).addAll(neighbours);

                // Links are bidirectional. Adding the back-link can overfill the neighbour,
                // so we re-run the selection on its list and keep only the best.
                int limit = (layer == 0) ? maxM0 : m;
                for (int neighbourId : neighbours) {
                    List<Integer> theirs = nodes.get(neighbourId).connections.get(layer);
                    theirs.add(id);
                    if (theirs.size() > limit) {
                        float[] theirVector = nodes.get(neighbourId).vector;
                        List<Candidate> asCandidates = new ArrayList<>(theirs.size());
                        for (int other : theirs) {
                            asCandidates.add(
                                    new Candidate(other, distance(theirVector, nodes.get(other).vector)));
                        }
                        asCandidates.sort(null);
                        List<Integer> pruned = selectNeighbours(theirVector, asCandidates, limit);
                        theirs.clear();
                        theirs.addAll(pruned);
                    }
                }

                // Everything we found here is a good place to start on the layer below.
                entryPoints = candidates.stream().map(Candidate::id).toList();
            }

            if (level > maxLevel) {
                maxLevel = level;
                entryPoint = id;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Picks a level from an exponentially decaying distribution: most nodes land on layer 0,
     * roughly 1/M of them get promoted one layer up, and so on. That is what makes the top
     * layers sparse enough to act as a highway.
     */
    private int randomLevel() {
        double r = random.nextDouble();
        if (r <= 0.0) r = Double.MIN_VALUE;
        return (int) (-Math.log(r) * levelMultiplier);
    }

    // ---------------------------------------------------------------- search

    @Override
    public List<SearchHit> search(float[] query, int k, int ef) {
        if (k <= 0) return List.of();
        float[] q = query.clone();
        if (q.length != dim) {
            throw new IllegalArgumentException(
                    "Expected dimension " + dim + " but got " + q.length);
        }
        if (metric.requiresNormalisation()) {
            DistanceMetric.normalise(q);
        }

        lock.readLock().lock();
        try {
            if (entryPoint == -1) return List.of();

            // Same downhill walk as during insert, but all the way to layer 1.
            int current = entryPoint;
            float currentDist = distance(q, nodes.get(current).vector);
            for (int layer = maxLevel; layer > 0; layer--) {
                boolean improved = true;
                while (improved) {
                    improved = false;
                    for (int neighbour : connectionsAt(current, layer)) {
                        float d = distance(q, nodes.get(neighbour).vector);
                        if (d < currentDist) {
                            currentDist = d;
                            current = neighbour;
                            improved = true;
                        }
                    }
                }
            }

            // Layer 0 holds every vector, so this is where the real search happens.
            int breadth = Math.max(ef, k);
            List<Candidate> found = sortedAscending(searchLayer(q, List.of(current), breadth, 0));

            List<SearchHit> hits = new ArrayList<>(k);
            for (Candidate c : found) {
                if (deleted.contains(c.id)) continue; // tombstoned: still walkable, never returned
                hits.add(SearchHit.of(nodes.get(c.id).externalId, c.distance, metric));
                if (hits.size() == k) break;
            }
            return hits;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * The engine room. Explores one layer starting from the given entry points and returns
     * the {@code ef} closest nodes it saw.
     *
     * <p>Two heaps do the work: {@code candidates} is a min-heap of places we still want to
     * expand (closest first), {@code results} is a max-heap of the best nodes so far
     * (worst on top so we can evict it in O(log ef)). We stop when the nearest unexplored
     * candidate is further away than the worst result we already hold - going further
     * cannot improve the answer.
     */
    private PriorityQueue<Candidate> searchLayer(
            float[] query, List<Integer> entryPoints, int ef, int layer) {

        Set<Integer> visited = new HashSet<>();
        PriorityQueue<Candidate> candidates = new PriorityQueue<>();
        PriorityQueue<Candidate> results = new PriorityQueue<>(Comparator.reverseOrder());

        for (int ep : entryPoints) {
            if (!visited.add(ep)) continue;
            float d = distance(query, nodes.get(ep).vector);
            candidates.add(new Candidate(ep, d));
            results.add(new Candidate(ep, d));
        }
        while (results.size() > ef) {
            results.poll();
        }

        while (!candidates.isEmpty()) {
            Candidate closest = candidates.poll();
            if (results.size() >= ef && closest.distance > results.peek().distance) {
                break;
            }
            for (int neighbour : connectionsAt(closest.id, layer)) {
                if (!visited.add(neighbour)) continue;
                float d = distance(query, nodes.get(neighbour).vector);
                if (results.size() < ef || d < results.peek().distance) {
                    candidates.add(new Candidate(neighbour, d));
                    results.add(new Candidate(neighbour, d));
                    if (results.size() > ef) {
                        results.poll();
                    }
                }
            }
        }
        return results;
    }

    /**
     * Chooses which candidates actually become neighbours.
     *
     * <p>Naively you would keep the M closest. The paper's heuristic does something smarter:
     * it skips a candidate that sits closer to an already-chosen neighbour than to the node
     * we are linking. That keeps the neighbour set spread out in different directions instead
     * of clustered on one side, which is what preserves long-range connectivity and stops the
     * graph fragmenting into islands. It is the single detail that most affects recall.
     */
    private List<Integer> selectNeighbours(float[] base, List<Candidate> candidates, int limit) {
        List<Integer> chosen = new ArrayList<>(limit);
        for (Candidate candidate : candidates) {
            if (chosen.size() >= limit) break;
            float[] candidateVector = nodes.get(candidate.id).vector;
            boolean keep = true;
            for (int alreadyChosen : chosen) {
                float toChosen = distance(candidateVector, nodes.get(alreadyChosen).vector);
                if (toChosen < candidate.distance) {
                    keep = false;
                    break;
                }
            }
            if (keep) chosen.add(candidate.id);
        }
        // If the heuristic was too strict we top up with the nearest leftovers.
        if (chosen.size() < limit) {
            for (Candidate candidate : candidates) {
                if (chosen.size() >= limit) break;
                if (!chosen.contains(candidate.id)) chosen.add(candidate.id);
            }
        }
        return chosen;
    }

    // ---------------------------------------------------------------- delete

    /**
     * Tombstone delete. We do not rip the node out of the graph, because its edges are what
     * hold neighbouring regions together - removing them can strand other vectors and silently
     * wreck recall. Instead we hide it from results and rebuild the index periodically.
     */
    @Override
    public boolean remove(long externalId) {
        lock.writeLock().lock();
        try {
            Integer id = internalIdByExternalId.get(externalId);
            if (id == null) return false;
            return deleted.add(id);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ---------------------------------------------------------------- persistence

    /** Writes the whole graph to disk so a restart does not mean re-embedding everything. */
    public void save(Path path) throws IOException {
        lock.readLock().lock();
        try (OutputStream fileOut = Files.newOutputStream(path);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fileOut))) {

            out.writeInt(MAGIC);
            out.writeInt(FORMAT_VERSION);
            out.writeInt(dim);
            out.writeUTF(metric.name());
            out.writeInt(m);
            out.writeInt(efConstruction);
            out.writeInt(entryPoint);
            out.writeInt(maxLevel);
            out.writeInt(nodes.size());

            for (Node node : nodes) {
                out.writeLong(node.externalId);
                out.writeInt(node.level());
                for (float value : node.vector) {
                    out.writeFloat(value);
                }
                for (List<Integer> perLayer : node.connections) {
                    out.writeInt(perLayer.size());
                    for (int neighbour : perLayer) {
                        out.writeInt(neighbour);
                    }
                }
            }
            out.writeInt(deleted.size());
            for (int id : deleted) {
                out.writeInt(id);
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Reads back an index written by {@link #save(Path)}. */
    public static HnswIndex load(Path path) throws IOException {
        try (InputStream fileIn = Files.newInputStream(path);
             DataInputStream in = new DataInputStream(new BufferedInputStream(fileIn))) {

            if (in.readInt() != MAGIC) {
                throw new IOException("Not a SemanticDocs index file: " + path);
            }
            int version = in.readInt();
            if (version != FORMAT_VERSION) {
                throw new IOException("Index format v" + version + " is not supported");
            }
            int dim = in.readInt();
            DistanceMetric metric = DistanceMetric.valueOf(in.readUTF());
            int m = in.readInt();
            int efConstruction = in.readInt();
            int entryPoint = in.readInt();
            int maxLevel = in.readInt();
            int nodeCount = in.readInt();

            HnswIndex index = new HnswIndex(dim, metric, m, efConstruction);
            for (int i = 0; i < nodeCount; i++) {
                long externalId = in.readLong();
                int level = in.readInt();
                float[] vector = new float[dim];
                for (int d = 0; d < dim; d++) {
                    vector[d] = in.readFloat();
                }
                Node node = new Node(i, externalId, vector, level);
                for (int layer = 0; layer <= level; layer++) {
                    int count = in.readInt();
                    List<Integer> perLayer = node.connections.get(layer);
                    for (int c = 0; c < count; c++) {
                        perLayer.add(in.readInt());
                    }
                }
                index.nodes.add(node);
                index.internalIdByExternalId.put(externalId, i);
            }
            int deletedCount = in.readInt();
            for (int i = 0; i < deletedCount; i++) {
                index.deleted.add(in.readInt());
            }
            index.entryPoint = entryPoint;
            index.maxLevel = maxLevel;
            return index;
        }
    }

    private static final int MAGIC = 0x53444F43; // "SDOC"
    private static final int FORMAT_VERSION = 1;

    // ---------------------------------------------------------------- helpers and stats

    private List<Integer> connectionsAt(int nodeId, int layer) {
        List<List<Integer>> all = nodes.get(nodeId).connections;
        return layer < all.size() ? all.get(layer) : List.of();
    }

    private float distance(float[] a, float[] b) {
        return metric.distance(a, b);
    }

    private static List<Candidate> sortedAscending(PriorityQueue<Candidate> heap) {
        List<Candidate> list = new ArrayList<>(heap);
        list.sort(null);
        return list;
    }

    @Override
    public int size() {
        lock.readLock().lock();
        try {
            return nodes.size() - deleted.size();
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
        return "HNSW(M=" + m + ",efC=" + efConstruction + ")";
    }

    /** Numbers for the /api/index/stats endpoint and for the README. */
    public IndexStats stats() {
        lock.readLock().lock();
        try {
            long edges = 0;
            for (Node node : nodes) {
                for (List<Integer> perLayer : node.connections) {
                    edges += perLayer.size();
                }
            }
            long vectorBytes = (long) nodes.size() * dim * Float.BYTES;
            long edgeBytes = edges * 4L;
            return new IndexStats(
                    nodes.size(), deleted.size(), maxLevel, m, efConstruction,
                    edges, vectorBytes + edgeBytes);
        } finally {
            lock.readLock().unlock();
        }
    }

    public record IndexStats(
            int nodeCount,
            int deletedCount,
            int maxLevel,
            int m,
            int efConstruction,
            long edgeCount,
            long approximateBytes) {
    }
}
