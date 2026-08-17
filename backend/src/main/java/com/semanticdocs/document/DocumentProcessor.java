package com.semanticdocs.document;

import com.semanticdocs.embedding.EmbeddingProvider;
import com.semanticdocs.vectorindex.VectorIndexService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * extract -> chunk -> embed -> save -> index
 *
 * <p>The whole pipeline for one document, inside one transaction. If embedding fails halfway,
 * the chunks written before it are rolled back, so a retry starts from a clean slate instead
 * of appending a second copy of the first half.
 *
 * <p>One deliberate asymmetry: the database rolls back, but the in-memory index does not.
 * That is why vectors are pushed into the index only at the very end, after every database
 * write has succeeded. Ordering side effects so that the non-transactional one happens last
 * is the standard way to keep two stores consistent without a distributed transaction.
 */
@Service
public class DocumentProcessor {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessor.class);

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final EmbeddingRepository embeddingRepository;
    private final TextExtractor textExtractor;
    private final Chunker chunker;
    private final EmbeddingProvider embeddingProvider;
    private final VectorIndexService indexService;

    public DocumentProcessor(DocumentRepository documentRepository,
                             ChunkRepository chunkRepository,
                             EmbeddingRepository embeddingRepository,
                             TextExtractor textExtractor,
                             Chunker chunker,
                             EmbeddingProvider embeddingProvider,
                             VectorIndexService indexService) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.embeddingRepository = embeddingRepository;
        this.textExtractor = textExtractor;
        this.chunker = chunker;
        this.embeddingProvider = embeddingProvider;
        this.indexService = indexService;
    }

    @Transactional
    public void process(Long documentId) {
        Document document = documentRepository.findById(documentId).orElseThrow();
        document.setStatus(DocumentStatus.PROCESSING);
        document.setErrorMessage(null);
        documentRepository.saveAndFlush(document);

        // 1. File bytes to plain text
        String text = textExtractor.extract(Path.of(document.getStoragePath()));

        // 2. Text to overlapping chunks
        List<Chunker.TextChunk> pieces = chunker.split(text);
        if (pieces.isEmpty()) {
            throw new IllegalStateException("Document produced no chunks");
        }

        // 3. Save chunks first: we need their generated ids as the labels in the vector index
        List<Chunk> toSave = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            Chunker.TextChunk piece = pieces.get(i);
            toSave.add(new Chunk(document, i, piece.content(), piece.start(), piece.end(), null));
        }
        List<Chunk> chunks = chunkRepository.saveAll(toSave);

        // 4. Embed every chunk. The slow step; the provider batches internally.
        List<String> texts = chunks.stream().map(Chunk::getContent).toList();
        List<float[]> vectors = embeddingProvider.embedDocuments(texts);

        // 5. Vectors to Postgres (durable), then to the graph (fast).
        List<ChunkEmbedding> embeddings = new ArrayList<>(chunks.size());
        List<Long> ids = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Long chunkId = chunks.get(i).getId();
            ids.add(chunkId);
            embeddings.add(new ChunkEmbedding(
                    chunkId, embeddingProvider.modelName(), vectors.get(i)));
        }
        embeddingRepository.saveAll(embeddings);
        indexService.addAll(ids, vectors);

        document.setChunkCount(chunks.size());
        document.setStatus(DocumentStatus.READY);
        document.setIndexedAt(Instant.now());
        documentRepository.save(document);

        log.info("Indexed document {} into {} chunks", documentId, chunks.size());
    }

    /**
     * REQUIRES_NEW because the caller's transaction has already been marked rollback-only by
     * the exception. Joining it would mean this write gets rolled back too and the user would
     * never see why their upload failed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long documentId, String message) {
        documentRepository.findById(documentId).ifPresent(document -> {
            document.setStatus(DocumentStatus.FAILED);
            String text = (message == null || message.isBlank()) ? "Unknown error" : message;
            document.setErrorMessage(text.length() > 500 ? text.substring(0, 500) : text);
            documentRepository.save(document);
        });
    }
}
