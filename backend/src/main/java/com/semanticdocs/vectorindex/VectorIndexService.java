package com.semanticdocs.vectorindex;

import com.semanticdocs.config.AppProperties;
import com.semanticdocs.document.ChunkEmbedding;
import com.semanticdocs.document.EmbeddingRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Owns the single in-memory index for the whole application and keeps it durable.
 *
 * <p>Three problems get solved here, and each one is a fair interview question.
 *
 * <p><b>"Where does the index live?"</b> In the heap of this process, as one shared bean.
 * That is why search is fast and why this service, not the index class, worries about
 * lifecycle. It is also the honest limitation of the design: one process owns the index, so
 * scaling out means sharding or moving the index behind its own service.
 *
 * <p><b>"What happens on restart?"</b> The graph is serialised to disk. On startup we load
 * that file. If it is missing or from an older format, we rebuild from the embeddings table,
 * which is the real source of truth - the file is only a cache of work already done.
 *
 * <p><b>"What if the process is killed?"</b> A periodic flush plus a shutdown hook. Worst
 * case we lose the vectors added since the last flush, and those get rebuilt from Postgres.
 */
@Service
public class VectorIndexService {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexService.class);

    private final AppProperties properties;
    private final EmbeddingRepository embeddingRepository;
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    private volatile HnswIndex index;

    public VectorIndexService(AppProperties properties, EmbeddingRepository embeddingRepository) {
        this.properties = properties;
        this.embeddingRepository = embeddingRepository;
    }

    @PostConstruct
    public void warmUp() {
        Path file = Path.of(properties.getStorage().getIndexFile());
        boolean rebuild = properties.getIndex().isRebuildOnStart();

        if (!rebuild && Files.exists(file)) {
            try {
                long start = System.currentTimeMillis();
                index = HnswIndex.load(file);
                log.info("Loaded index from {} with {} vectors in {} ms",
                        file, index.size(), System.currentTimeMillis() - start);
                return;
            } catch (IOException ex) {
                log.warn("Index file unreadable ({}), rebuilding from database", ex.getMessage());
            }
        }
        rebuildFromDatabase();
    }

    /** Replays every stored embedding into a fresh graph. */
    public synchronized void rebuildFromDatabase() {
        long start = System.currentTimeMillis();
        HnswIndex fresh = newIndex();
        List<ChunkEmbedding> all = embeddingRepository.findAllOrdered();
        for (ChunkEmbedding embedding : all) {
            if (embedding.getDimension() != properties.getEmbedding().getDimension()) {
                log.warn("Skipping chunk {}: stored dimension {} does not match configured {}",
                        embedding.getChunkId(), embedding.getDimension(),
                        properties.getEmbedding().getDimension());
                continue;
            }
            fresh.add(embedding.getChunkId(), embedding.toFloats());
        }
        index = fresh;
        dirty.set(true);
        log.info("Rebuilt index with {} vectors in {} ms",
                fresh.size(), System.currentTimeMillis() - start);
    }

    private HnswIndex newIndex() {
        return new HnswIndex(
                properties.getEmbedding().getDimension(),
                DistanceMetric.COSINE,
                properties.getIndex().getM(),
                properties.getIndex().getEfConstruction());
    }

    public void add(long chunkId, float[] vector) {
        index.add(chunkId, vector);
        dirty.set(true);
    }

    public void addAll(List<Long> chunkIds, List<float[]> vectors) {
        for (int i = 0; i < chunkIds.size(); i++) {
            index.add(chunkIds.get(i), vectors.get(i));
        }
        dirty.set(true);
    }

    public void remove(long chunkId) {
        if (index.remove(chunkId)) {
            dirty.set(true);
        }
    }

    public List<SearchHit> search(float[] query, int k) {
        return index.search(query, k, properties.getIndex().getEfSearch());
    }

    public List<SearchHit> search(float[] query, int k, int ef) {
        return index.search(query, k, ef);
    }

    public HnswIndex.IndexStats stats() {
        return index.stats();
    }

    public int size() {
        return index.size();
    }

    /** Flushes every 5 minutes, but only when something actually changed. */
    @Scheduled(fixedDelayString = "PT5M")
    public void flushIfDirty() {
        if (dirty.compareAndSet(true, false)) {
            persist();
        }
    }

    @PreDestroy
    public void persistOnShutdown() {
        persist();
    }

    private synchronized void persist() {
        Path file = Path.of(properties.getStorage().getIndexFile());
        try {
            Files.createDirectories(file.getParent());
            // Write to a temp file and move it into place, so a crash mid-write cannot
            // leave a half-written index that fails to load on the next start.
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            index.save(temp);
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("Saved index ({} vectors) to {}", index.size(), file);
        } catch (IOException ex) {
            log.error("Could not save the index to disk", ex);
            dirty.set(true);
        }
    }
}
