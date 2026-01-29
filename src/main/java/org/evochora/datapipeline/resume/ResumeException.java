package org.evochora.datapipeline.resume;

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
 * This is a RuntimeException because resume failures typically indicate
 * configuration or data issues that cannot be recovered from automatically.
 */
public class ResumeException extends RuntimeException {

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
