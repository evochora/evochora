package org.evochora.datapipeline.utils;

/**
 * Shared utility methods for JMS-related operations.
 */
public final class JmsUtils {

    private JmsUtils() {}

    /**
     * Checks if an exception was caused by thread interruption (shutdown signal).
     * <p>
     * Walks the cause chain to detect a wrapped {@link InterruptedException}.
     * JMS providers (Artemis, ActiveMQ) often wrap {@code InterruptedException}
     * inside {@code JMSException} during graceful shutdown.
     *
     * @param e the exception to check
     * @return {@code true} if the root cause is an {@code InterruptedException}
     */
    public static boolean isInterruptedException(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof InterruptedException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
