package org.evochora.compiler.diagnostics;

/**
 * Thrown after an error has been reported to the {@link DiagnosticsEngine}, to unwind to the
 * point where the phase resumes: the parser continues with the next statement, the
 * preprocessor with the next line. The exception carries no error of its own; what the
 * programmer has to know is in the diagnostics already. A phase catches this type and nothing
 * broader, so an exception a handler did not report is a defect that leaves the phase.
 */
public class ErrorRecoveryException extends RuntimeException {

    /**
     * Creates the exception for an error that has just been reported.
     *
     * @param reportedMessage The message that was reported to the diagnostics, kept for
     *                        anyone looking at the exception itself.
     */
    public ErrorRecoveryException(String reportedMessage) {
        super(reportedMessage);
    }
}
