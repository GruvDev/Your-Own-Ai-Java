package com.semanticdocs.document;

/**
 * Where a document is in the pipeline.
 *
 * <p>This column is what makes the upload endpoint able to return in milliseconds. The HTTP
 * request only saves the file and writes PENDING; the slow work happens on a background
 * thread and moves the row through PROCESSING to READY. It is also our crash recovery:
 * on startup, anything stuck in PROCESSING can be picked up and retried.
 */
public enum DocumentStatus {
    PENDING,
    PROCESSING,
    READY,
    FAILED
}
