package org.evochora.datapipeline.api.resources;

import java.time.Instant;

/**
 * Represents an operational error that occurred within a pipeline component.
 * <p>
 * This record is used to provide detailed, structured information about errors
 * for monitoring and debugging purposes.
 *
 * @param timestamp The timestamp of when the error occurred.
 * @param errorType A category for the error (e.g., "CONNECTION_FAILED", "IO_ERROR").
 * @param message   A human-readable description of the error.
 * @param details   Optional additional context, such as a stack trace or relevant data.
 */
public record OperationalError(
    Instant timestamp,
    String errorType,
    String message,
    String details
) {
}