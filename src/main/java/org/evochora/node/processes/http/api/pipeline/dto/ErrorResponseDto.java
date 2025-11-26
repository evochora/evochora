package org.evochora.node.processes.http.api.pipeline.dto;

import java.time.Instant;

/**
 * Response DTO for error responses.
 * <p>
 * Provides a consistent error response format across all API endpoints.
 *
 * @param timestamp ISO-8601 timestamp when the error occurred
 * @param status    HTTP status code
 * @param error     HTTP status message (e.g., "Not Found", "Conflict")
 * @param message   Human-readable error message
 */
public record ErrorResponseDto(
    String timestamp,
    int status,
    String error,
    String message
) {
    /**
     * Creates an ErrorResponseDto with the current timestamp.
     *
     * @param status  HTTP status code
     * @param error   HTTP status message
     * @param message Human-readable error message
     * @return A new ErrorResponseDto instance
     */
    public static ErrorResponseDto of(final int status, final String error, final String message) {
        return new ErrorResponseDto(
            Instant.now().toString(),
            status,
            error,
            message
        );
    }
}

