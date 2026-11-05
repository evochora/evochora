package org.evochora.datapipeline.api.resources;

/**
 * A failure whose cause is known: one the system anticipates and can name, such as a resource that
 * is gone, data that never arrived or a configuration that does not fit, as opposed to a defect.
 * <p>
 * Its message says what went wrong, so it is logged as that one line and without a stack trace,
 * which exists to find a defect and here would only bury the message. Code that meets a failure
 * it can name, an exception of a library among them, passes it on in this type, with the original
 * as its cause. Every other exception counts as a defect and is logged with its stack trace.
 * <p>
 * Where a caller has to tell one known failure from the others, a subclass names it.
 */
public class OperationalException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the failure with the message that says what went wrong.
     *
     * @param message what went wrong, on one line
     */
    public OperationalException(String message) {
        super(message);
    }

    /**
     * Creates the failure with the message that says what went wrong and the exception it was
     * met as.
     *
     * @param message what went wrong, on one line
     * @param cause the exception the failure was met as
     */
    public OperationalException(String message, Throwable cause) {
        super(message, cause);
    }
}
