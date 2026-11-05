package org.evochora.datapipeline.resume;

import org.evochora.datapipeline.api.resources.OperationalException;

/**
 * Thrown when simulation resume fails.
 * <p>
 * This exception indicates that the simulation cannot be resumed from the
 * specified checkpoint. Possible causes include:
 * <ul>
 *   <li>Metadata not found for the run-id</li>
 *   <li>No tick data (batch files) found</li>
 *   <li>Corrupted or unreadable checkpoint data</li>
 *   <li>Plugin instantiation failure</li>
 * </ul>
 * <p>
 * Its cause is known, a configuration or data the checkpoint does not fit, so it is an
 * {@link OperationalException}: logged by its message, without a stack trace.
 */
public class ResumeException extends OperationalException {

    /**
     * Creates a ResumeException with the specified message.
     *
     * @param message Description of the resume failure
     */
    public ResumeException(String message) {
        super(message);
    }

    /**
     * Creates a ResumeException with the specified message and cause.
     *
     * @param message Description of the resume failure
     * @param cause The underlying exception that caused the failure
     */
    public ResumeException(String message, Throwable cause) {
        super(message, cause);
    }
}
