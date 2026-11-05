package org.evochora.datapipeline.api.resources;

import java.time.Instant;

/**
 * An error a pipeline component recorded, one entry of the list its status reports.
 * <p>
 * A component records the failures that affect what it delivers: a transient one it continues
 * after, the fatal one of a resource, which does not stop by itself, and the one that ended a
 * service, which {@code AbstractService} records. The entry carries the error's type, message and
 * details for monitoring; whether it came from a known cause or a defect is the log's to tell.
 *
 * @param timestamp The timestamp of when the error occurred.
 * @param errorType A category for the error (e.g., "CONNECTION_FAILED", "IO_ERROR").
 * @param message   A human-readable description of the error.
 * @param details   Optional additional context, such as a stack trace or relevant data.
 */
public record RecordedError(
    Instant timestamp,
    String errorType,
    String message,
    String details
) {

    /**
     * The type of the error that ended a running service: an exception or {@link Error} its own
     * work let through. It is recorded so that a service in error reports why. For a known cause,
     * an {@link OperationalException}, its message is that of the cause chain; for a defect it
     * names the innermost cause and points to the stack trace in the log.
     */
    public static final String SERVICE_FAILED = "SERVICE_FAILED";
}