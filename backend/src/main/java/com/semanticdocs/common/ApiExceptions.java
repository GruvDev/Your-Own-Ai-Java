package com.semanticdocs.common;

/**
 * Small typed exceptions so services can signal intent without importing anything web related.
 * The @RestControllerAdvice maps each one to the right HTTP status in a single place.
 */
public final class ApiExceptions {

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String message) {
            super(message);
        }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    /** Something downstream (Ollama, disk) failed. Maps to 502. */
    public static class UpstreamException extends RuntimeException {
        public UpstreamException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private ApiExceptions() {
    }
}
