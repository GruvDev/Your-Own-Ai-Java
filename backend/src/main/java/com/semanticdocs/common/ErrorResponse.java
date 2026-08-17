package com.semanticdocs.common;

import java.time.Instant;
import java.util.Map;

/**
 * Every error the API returns has this shape. A predictable error body is the difference
 * between a frontend that can show a useful message and one that shows "something went wrong".
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> fieldErrors) {

    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(Instant.now(), status, error, message, path, Map.of());
    }
}
