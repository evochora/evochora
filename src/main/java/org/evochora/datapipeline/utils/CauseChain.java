package org.evochora.datapipeline.utils;

/**
 * Renders the messages of an exception and its causes as one line, for a log message about a
 * failure whose cause is known.
 * <p>
 * Such a message carries no stack trace: the trace exists to find out what went wrong, and here
 * that is known. What the operator needs is the diagnosis the cause chain holds - a pool that
 * cannot connect wraps the refused connection, a console that cannot start wraps the port in use -
 * and that is what this puts into the line.
 */
public final class CauseChain {

    private static final String SEPARATOR = "; caused by: ";

    private CauseChain() {
    }

    /**
     * Joins the messages of the throwable and every cause below it. A cause without a message
     * contributes its class name; a cause repeating its wrapper's message is left out, so that a
     * wrapper that only re-states its cause does not double the line.
     *
     * @param throwable the exception whose chain is rendered
     * @return the messages, outermost first, separated by {@value #SEPARATOR}
     */
    public static String messages(Throwable throwable) {
        StringBuilder line = new StringBuilder();
        String previous = null;
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            String message = current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
            if (message.equals(previous)) {
                continue;
            }
            if (line.length() > 0) {
                line.append(SEPARATOR);
            }
            line.append(message);
            previous = message;
        }
        return line.toString();
    }
}
