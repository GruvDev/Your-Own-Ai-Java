package com.semanticdocs.document;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Picks up documents that were mid-flight when the process last died.
 *
 * <p>Without this, a restart during ingestion leaves a row stuck in PROCESSING forever and
 * the user watches a spinner that will never stop. The status column makes recovery a simple
 * query, which is the practical payoff of modelling state in the database rather than only in
 * memory.
 */
@Component
public class IngestionRecovery {

    private static final Logger log = LoggerFactory.getLogger(IngestionRecovery.class);

    private final DocumentRepository documentRepository;
    private final IngestionService ingestionService;

    public IngestionRecovery(DocumentRepository documentRepository,
                             IngestionService ingestionService) {
        this.documentRepository = documentRepository;
        this.ingestionService = ingestionService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void requeueInterrupted() {
        List<Document> interrupted = documentRepository.findByStatus(DocumentStatus.PROCESSING);
        List<Document> pending = documentRepository.findByStatus(DocumentStatus.PENDING);
        for (Document document : interrupted) {
            log.warn("Requeueing document {} interrupted mid-processing", document.getId());
            ingestionService.ingest(document.getId());
        }
        for (Document document : pending) {
            log.info("Requeueing document {} that never started", document.getId());
            ingestionService.ingest(document.getId());
        }
    }
}
