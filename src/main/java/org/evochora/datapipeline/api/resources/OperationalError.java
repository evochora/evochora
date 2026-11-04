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
     * The type of an error that is a defect of Evochora itself rather than anything a user can
     * change, such as an exception no component knows how to handle. It is recorded for a service
     * that such a defect ended, so that the service reports why it is in error; its message says
     * so and names the innermost cause, and the stack trace is in the log.
     */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
}