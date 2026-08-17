package com.semanticdocs.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Hands a document to the background pool and makes sure a failure is recorded.
 *
 * <p>Notice that the real work lives in a different bean. That is not decoration - it is
 * required. Spring implements @Async and @Transactional with a proxy that wraps the bean,
 * and a proxy can only intercept calls that arrive from outside. If process() lived here and
 * ingest() called it directly, the call would go straight to the raw object: no thread
 * switch, no transaction, and no warning. Self-invocation silently disabling annotations is
 * one of the most common Spring bugs there is.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final DocumentProcessor processor;

    public IngestionService(DocumentProcessor processor) {
        this.processor = processor;
    }

    @Async("ingestionExecutor")
    public void ingest(Long documentId) {
        log.info("Starting ingestion for document {} on thread {}",
                documentId, Thread.currentThread().getName());
        try {
            processor.process(documentId);
        } catch (Exception ex) {
            // The transaction inside process() has already rolled back at this point, so the
            // failure is written in its own separate transaction.
            log.error("Ingestion failed for document {}", documentId, ex);
            processor.markFailed(documentId, ex.getMessage());
        }
    }
}
