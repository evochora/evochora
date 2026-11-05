package org.evochora.datapipeline.api.resources;

import org.evochora.util.CauseChain;

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

    /**
     * The line a failure is logged with when its cause is known, or {@code null} when it may be a
     * defect, which is logged with its stack trace instead. This is the one place that tells the
     * two apart.
     * <p>
     * A cause is known when the chain holds an {@code OperationalException}, told from that link
     * on, or when the failure itself is a checked exception, told from there: a checked exception
     * caught straight from a call is a failure of the surroundings the call reaches, storage or a
     * database. A checked exception further down the chain does not count, because Java wraps
     * whatever a constructor called by reflection throws, defects as well, in a checked
     * {@link java.lang.reflect.InvocationTargetException}.
     *
     * @param failure the exception that was caught
     * @return the messages of the known cause on one line, or {@code null} for a possible defect
     */
    public static String reasonOf(Throwable failure) {
        OperationalException known = CauseChain.find(failure, OperationalException.class);
        if (known != null) {
            return CauseChain.messages(known);
        }
        if (!(failure instanceof RuntimeException) && !(failure instanceof Error)) {
            return CauseChain.messages(failure);
        }
        return null;
    }
}
