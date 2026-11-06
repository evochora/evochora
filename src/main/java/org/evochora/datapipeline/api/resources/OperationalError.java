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

    /**
     * The type of the error that ended a running service: an exception or {@link Error} its own
     * work let through, whether a defect or a failure of its surroundings such as data that never
     * arrived. It is recorded so that a service in error reports why; its message names the
     * innermost cause, and the stack trace in the log tells which of the two it was.
     */
    public static final String SERVICE_FAILED = "SERVICE_FAILED";
}