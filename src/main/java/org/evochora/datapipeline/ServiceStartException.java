package org.evochora.datapipeline;

/**
 * A start of a service that failed for a cause the {@link ServiceManager} does not recognise: a
 * defect, exception or {@link Error}, carried as the cause.
 * <p>
 * It sets the failure of a start apart from the {@link IllegalStateException} and
 * {@link IllegalArgumentException} the manager throws for a start it refuses, a service already
 * running or one not defined, so that a caller does not take a defect for a request it made wrong.
 * It is not logged where it is thrown: whoever handles it logs it, the manager's start of all
 * services for one, the HTTP API for a single start.
 */
public class ServiceStartException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param serviceName the configured name of the service whose start failed
     * @param cause       the failure
     */
    public ServiceStartException(String serviceName, Throwable cause) {
        super("Failed to create and start a new instance for service '" + serviceName + "'.", cause);
    }
}
