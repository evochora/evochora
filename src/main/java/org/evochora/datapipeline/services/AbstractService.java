package org.evochora.datapipeline.services;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.services.IService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

/**
 * An abstract base class for all services, providing common lifecycle management,
 * thread handling, resource access utilities, and error tracking. Subclasses must 
 * implement the {@link #run()} method to define their specific logic.
 * <p>
 * Error Tracking: Services can use {@link #recordError(String, String, String)} to
 * track transient errors that affect data quality but don't require service termination.
 */
public abstract class AbstractService implements IService, IMonitorable {

    protected final Logger log = LoggerFactory.getLogger(this.getClass());
    protected final String serviceName;
    protected final Config options;
    protected final Map<String, List<IResource>> resources;
    private final AtomicReference<State> currentState = new AtomicReference<>(State.STOPPED);
    private final Object pauseLock = new Object();
    private Thread serviceThread;
    private final int shutdownTimeoutSeconds;
    
    /**
     * Flag indicating that a graceful shutdown has been requested.
     * <p>
     * Services should check {@link #isStopRequested()} in their main loop
     * and exit gracefully when this flag is set, completing any in-progress
     * operations before returning from {@link #run()}.
     */
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    /**
     * Tracks whether the service is currently in a safe-to-interrupt state (WAITING)
     * or performing IO writes that must not be interrupted (PROCESSING).
     * <p>
     * Volatile for visibility across the service thread and the stop() caller thread.
     * Default is WAITING — services that never set this get immediate interrupt on shutdown.
     */
    private volatile ShutdownPhase currentShutdownPhase = ShutdownPhase.WAITING;
    
    /**
     * Collection of operational errors that occurred during service execution.
     * These are transient errors that don't stop the service but may affect data quality.
     * Limited to MAX_ERRORS to prevent unbounded memory growth.
     * <p>
     * Private to enforce use of {@link #recordError(String, String, String)} method.
     * Subclasses must not access this directly.
     */
    private final ConcurrentLinkedDeque<OperationalError> errors = new ConcurrentLinkedDeque<>();
    
    /**
     * Maximum number of errors to keep in memory. When exceeded, oldest errors are removed.
     * This prevents OOM in long-running services with frequent errors.
     * Subclasses can override this value if needed.
     */
    protected int getMaxErrors() {
        return 10000;
    }

    /**
     * Constructs an AbstractService with its configuration and resources.
     *
     * @param name      The name of the service instance.
     * @param options   The configuration for this service.
     * @param resources A map of resource ports to lists of resources.
     */
    protected AbstractService(String name, Config options, Map<String, List<IResource>> resources) {
        this.serviceName = name;
        this.options = options;
        this.resources = resources;
        
        // Read shutdown timeout from service options (default: 5 seconds)
        this.shutdownTimeoutSeconds = options.hasPath("shutdownTimeout")
            ? options.getInt("shutdownTimeout")
            : 5;
    }

    @Override
    public final void start() {
        if (!currentState.compareAndSet(State.STOPPED, State.RUNNING)) {
            throw new IllegalStateException(String.format("Cannot start service '%s' as it is already in state %s", serviceName, getCurrentState()));
        }
        // Reset stop flag for restart scenarios
        stopRequested.set(false);
        serviceThread = new Thread(this::runService);
        serviceThread.setName(serviceName);
        serviceThread.start();
        logStarted();
    }

    /**
     * Template method for logging service startup. Services can override this to provide
     * detailed startup information. Default implementation logs a simple message.
     */
    protected void logStarted() {
        log.info("{} started", this.getClass().getSimpleName());
    }

    @Override
    public final void stop() {
        State state = getCurrentState();
        if (state == State.RUNNING || state == State.PAUSED) {
            // ============================================================
            // PHASE-AWARE GRACEFUL SHUTDOWN
            // ============================================================
            // 1. Signal stop request + wake paused threads
            // 2. If WAITING → immediate interrupt (breaks blocking receive/poll)
            // 3. Wait for thread termination (full timeout as grace period)
            // 4. If still alive → force interrupt + WARN (regardless of phase)
            // 5. If still alive → ERROR state
            // ============================================================

            stopRequested.set(true);

            if (state == State.PAUSED) {
                synchronized (pauseLock) {
                    pauseLock.notifyAll();
                }
            }

            if (serviceThread != null) {
                try {
                    long totalTimeoutMs = shutdownTimeoutSeconds * 1000L;
                    long startTime = System.currentTimeMillis();
                    long pollIntervalMs = 50;

                    // WAITING services can be interrupted immediately — they are blocked on
                    // receive/poll calls that respond to interrupt without data corruption.
                    // PROCESSING services are mid-write to storage/database — interrupt would
                    // corrupt NIO FileChannels via ClosedByInterruptException.
                    if (getShutdownPhase() == ShutdownPhase.WAITING) {
                        serviceThread.interrupt();
                    }

                    // Wait for thread termination (full timeout as grace period for PROCESSING)
                    while (serviceThread.isAlive()
                            && (System.currentTimeMillis() - startTime) < totalTimeoutMs) {
                        serviceThread.join(pollIntervalMs);
                    }

                    // Force interrupt if thread did not terminate within timeout
                    if (serviceThread.isAlive()) {
                        log.warn("{} did not stop within {}s, forcing interrupt",
                            this.getClass().getSimpleName(), shutdownTimeoutSeconds);
                        serviceThread.interrupt();

                        // Brief wait for forced termination
                        serviceThread.join(1000);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("{} interrupted while waiting for service shutdown", this.getClass().getSimpleName());
                }

                if (serviceThread.isAlive()) {
                    log.error("{} thread did not stop within {} seconds! Forcing ERROR state.",
                        this.getClass().getSimpleName(), shutdownTimeoutSeconds);
                    currentState.set(State.ERROR);
                    return;
                }
            }

            if (getCurrentState() != State.STOPPED && getCurrentState() != State.ERROR) {
                log.warn("{} thread terminated but state is {}, setting to STOPPED",
                    this.getClass().getSimpleName(), getCurrentState());
                currentState.set(State.STOPPED);
            }
            log.debug("{} stopped", this.getClass().getSimpleName());
        } else {
            throw new IllegalStateException(String.format("Cannot stop service '%s' as it is in state %s", serviceName, state));
        }
    }

    @Override
    public final void pause() {
        if (!currentState.compareAndSet(State.RUNNING, State.PAUSED)) {
            throw new IllegalStateException(String.format("Cannot pause service '%s' as it is in state %s", serviceName, getCurrentState()));
        }
        log.info("{} paused", this.getClass().getSimpleName());
    }

    @Override
    public final void resume() {
        if (!currentState.compareAndSet(State.PAUSED, State.RUNNING)) {
            throw new IllegalStateException(String.format("Cannot resume service '%s' as it is in state %s", serviceName, getCurrentState()));
        }
        log.info("{} resumed", this.getClass().getSimpleName());
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
    }

    /**
     * Stops and then starts the service.
     */
    public void restart() {
        stop();
        try {
            if (serviceThread != null && serviceThread.isAlive()) {
                serviceThread.join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("{} interrupted while waiting for service to stop before restarting", this.getClass().getSimpleName(), e);
            // Restore STOPPED state if interruption occurs
            currentState.set(State.STOPPED);
            return;
        }
        start();
    }

    @Override
    public State getCurrentState() {
        return currentState.get();
    }

    /**
     * Wraps the service's run() method with error handling and state management.
     * <p>
     * <strong>Error Handling Strategy for Services:</strong>
     * <ul>
     *   <li><strong>Transient errors</strong>: Catch, log, collect in error collections, continue running</li>
     *   <li><strong>Fatal errors</strong>: Throw exception → automatically transitions to ERROR state</li>
     * </ul>
     * <p>
     * Services can handle exceptions before rethrowing:
     * <pre>
     * try {
     *     criticalOperation();
     * } catch (DatabaseException e) {
     *     sendToDLQ(data, e);  // Custom handling
     *     throw new RuntimeException("Fatal: Database connection lost", e);  // → ERROR state
     * }
     * </pre>
     */
    private void runService() {
        try {
            run();
        } catch (InterruptedException e) {
            log.debug("Service thread interrupted, shutting down.");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (isInterruptInduced(e)) {
                log.debug("Service thread interrupted, shutting down.");
                Thread.currentThread().interrupt();
            } else {
                log.error("{} stopped with ERROR due to {}",
                    this.getClass().getSimpleName(),
                    e.getClass().getSimpleName());
                log.debug("Exception details:", e);
                currentState.set(State.ERROR);
            }
        } finally {
            // If the service stopped for any reason other than an error, set state to STOPPED.
            if (getCurrentState() != State.ERROR) {
                currentState.set(State.STOPPED);
            }
            log.debug("Service thread for {} has terminated.", this.getClass().getSimpleName());
        }
    }

    /**
     * Checks whether an exception was induced by a Thread.interrupt() call by walking
     * the cause chain for interrupt-specific types. Libraries like Artemis wrap
     * InterruptedException in RuntimeExceptions (e.g., ActiveMQInterruptedException),
     * and NIO throws ClosedByInterruptException when a channel is interrupted mid-IO.
     */
    private static boolean isInterruptInduced(Throwable t) {
        for (Throwable current = t; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException
                    || current instanceof java.nio.channels.ClosedByInterruptException) {
                return true;
            }
        }
        return false;
    }

    /**
     * The main logic of the service, to be implemented by subclasses. This method
     * will be executed in a dedicated thread.
     * <p>
     * <strong>Graceful Shutdown Pattern:</strong>
     * Services should check {@link #isStopRequested()} in their main loop and exit
     * gracefully when it returns {@code true}. Services performing IO writes should
     * bracket their critical sections with {@link #setShutdownPhase(ShutdownPhase)}
     * to prevent interrupt-induced NIO corruption:
     * <pre>
     * &#64;Override
     * protected void run() throws InterruptedException {
     *     while (!isStopRequested()) {
     *         checkPause();
     *         // Default phase is WAITING → receive/poll gets interrupted immediately on shutdown
     *         try (var batch = queue.receiveBatch(...)) {
     *             if (batch.size() == 0) continue;
     *
     *             setShutdownPhase(ShutdownPhase.PROCESSING);
     *             Thread.interrupted(); // Clear interrupt flag from WAITING→PROCESSING race
     *
     *             storage.write(batch);
     *             batch.commit();
     *
     *             setShutdownPhase(ShutdownPhase.WAITING);
     *         }
     *     }
     * }
     * </pre>
     * <p>
     * <strong>IMPORTANT:</strong> Use timeouts on blocking operations (queue.poll, I/O)
     * so the loop can check isStopRequested() periodically.
     * <p>
     * <strong>Error Handling Guidelines for Services:</strong>
     * <p>
     * <strong>1. Transient Errors</strong> (service continues running):
     * <ul>
     *   <li>Use: {@code log.warn("message", args)} - NO exception parameter</li>
     *   <li>Use: {@link #recordError(String, String, String)} to track</li>
     *   <li>Do NOT throw exception</li>
     *   <li>Example: Single message send failed, one tick failed, temporary I/O error</li>
     * </ul>
     * 
     * <strong>2. Fatal Errors</strong> (service must stop):
     * <ul>
     *   <li>Use: {@code log.error("message with context", args)} - NO exception parameter</li>
     *   <li>Do NOT use recordError() - service stops anyway</li>
     *   <li>Throw exception - AbstractService will set ERROR state</li>
     *   <li>Example: Database connection lost, cannot compile program, resource unavailable</li>
     * </ul>
     * 
     * <strong>3. Normal Shutdown (InterruptedException):</strong>
     * <ul>
     *   <li>Use: {@code log.debug("message with context", args)} - provides context for debugging</li>
     *   <li>Do NOT use recordError() - this is not an error</li>
     *   <li>Re-throw the exception - AbstractService handles it as clean shutdown</li>
     *   <li>Example: Service stopped during queue.take(), interrupted during file polling</li>
     * </ul>
     * 
     * <strong>4. Retry Logic:</strong>
     * <ul>
     *   <li>During retry attempts: {@code log.debug()} - only for developer debugging</li>
     *   <li>After all retries exhausted: Follow transient or fatal error rules above</li>
     *   <li>Example: {@code catch(IOException e) { log.debug("Retry {}/{}", attempt, max); }}</li>
     * </ul>
     * 
     * <strong>Stack Traces:</strong> Exception stack traces are always logged at DEBUG level
     * by AbstractService. Services should never log exceptions with {@code log.error(..., e)}.
     *
     * @throws InterruptedException if the service thread is interrupted.
     */
    protected abstract void run() throws InterruptedException;

    /**
     * Blocks the current thread if the service is in the {@link State#PAUSED} state.
     * This method is intended to be called within the {@link #run()} loop of a service.
     * When the service is paused, this method will block until the service is resumed or stopped.
     *
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    protected void checkPause() throws InterruptedException {
        synchronized (pauseLock) {
            while (getCurrentState() == State.PAUSED) {
                log.debug("Service is paused, waiting...");
                pauseLock.wait();
                log.debug("Woke up from pause.");
            }
        }
    }
    
    /**
     * Checks if a graceful shutdown has been requested.
     * <p>
     * Services should check this method in their main loop and exit gracefully
     * when it returns {@code true}. This enables the Two-Phase Shutdown pattern:
     * <ol>
     *   <li>Phase 1: stopRequested is set, service completes current work and exits</li>
     *   <li>Phase 2: If service doesn't exit in time, Thread.interrupt() is called</li>
     * </ol>
     * <p>
     * <strong>Usage Example:</strong>
     * <pre>
     * &#64;Override
     * protected void run() throws InterruptedException {
     *     while (!isStopRequested()) {
     *         checkPause();
     *         
     *         // Process one item (will complete even if stop is requested mid-processing)
     *         T item = queue.poll(1, TimeUnit.SECONDS);
     *         if (item != null) {
     *             processItem(item);  // This completes fully
     *             sendAck(item);      // ACK also completes
     *         }
     *     }
     *     
     *     // Optional: Flush remaining work before exit
     *     flushPendingWork();
     * }
     * </pre>
     * <p>
     * <strong>Note:</strong> Blocking operations (queue.take(), I/O) should use
     * timeouts so the loop can check isStopRequested() periodically.
     *
     * @return {@code true} if a graceful shutdown has been requested
     */
    protected boolean isStopRequested() {
        return stopRequested.get();
    }

    @Override
    public ShutdownPhase getShutdownPhase() {
        return currentShutdownPhase;
    }

    /**
     * Sets the current shutdown phase. Subclasses call this to mark IO-critical sections.
     * <p>
     * Set to PROCESSING before storage/database writes and back to WAITING after commit.
     * When transitioning to PROCESSING, callers should also clear any pending interrupt
     * flag via {@code Thread.interrupted()} to prevent a race where stop() interrupted
     * the thread while it was still in WAITING but has since entered PROCESSING.
     *
     * @param phase the new shutdown phase
     */
    protected void setShutdownPhase(ShutdownPhase phase) {
        this.currentShutdownPhase = phase;
    }

    /**
     * Gets a single required resource for a given port, ensuring it matches the expected type.
     * <p>
     * <strong>Type Safety:</strong> While this method accepts any Class<T>, it is intended for
     * resource and capability interfaces. The runtime cast ensures type safety. Typically used
     * with IResource implementations or capability interfaces (e.g., IMetadataReader).
     *
     * @param portName     The name of the resource port.
     * @param expectedType The class of the expected resource type.
     * @param <T>          The expected type of the resource.
     * @return The single resource instance.
     * @throws IllegalStateException if the port is not configured, has no resources,
     *                               has more than one resource, or if the resource is of the wrong type.
     */
    protected <T> T getRequiredResource(String portName, Class<T> expectedType) {
        List<IResource> resourceList = resources.get(portName);
        if (resourceList == null) {
            throw new IllegalStateException("Resource port '" + portName + "' is not configured.");
        }
        if (resourceList.isEmpty()) {
            throw new IllegalStateException("Resource port '" + portName + "' has no configured resources, but exactly one is required.");
        }
        if (resourceList.size() > 1) {
            throw new IllegalStateException("Resource port '" + portName + "' has " + resourceList.size() + " resources, but exactly one is required.");
        }
        IResource resource = resourceList.get(0);
        if (!expectedType.isInstance(resource)) {
            throw new IllegalStateException("Resource at port '" + portName + "' is of type " + resource.getClass().getName() + ", but expected type is " + expectedType.getName());
        }
        return expectedType.cast(resource);
    }

    /**
     * Gets a list of all resources for a given port, ensuring they all match the expected type.
     *
     * @param portName     The name of the resource port.
     * @param expectedType The class of the expected resource type.
     * @param <T>          The expected type of the resources.
     * @return A list of resource instances, which may be empty if the port is not configured.
     * @throws IllegalStateException if any resource is not of the expected type.
     */
    protected <T> List<T> getResources(String portName, Class<T> expectedType) {
        List<IResource> resourceList = resources.getOrDefault(portName, java.util.Collections.emptyList());
        return resourceList.stream()
                .map(resource -> {
                    if (!expectedType.isInstance(resource)) {
                        throw new IllegalStateException("Resource at port '" + portName + "' is of type " + resource.getClass().getName() + ", but expected type is " + expectedType.getName());
                    }
                    return expectedType.cast(resource);
                })
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Gets a single optional resource for a given port, ensuring it matches the expected type.
     * <p>
     * <strong>Type Safety:</strong> While this method accepts any Class<T>, it is intended for
     * resource and capability interfaces. The runtime cast ensures type safety. Typically used
     * with IResource implementations or capability interfaces (e.g., IMetadataReader).
     *
     * @param portName     The name of the resource port.
     * @param expectedType The class of the expected resource type.
     * @param <T>          The expected type of the resource.
     * @return An Optional containing the single resource instance, or empty if not configured.
     * @throws IllegalStateException if the port has more than one resource or if the resource is of the wrong type.
     */
    protected <T> java.util.Optional<T> getOptionalResource(String portName, Class<T> expectedType) {
        List<IResource> resourceList = resources.get(portName);
        if (resourceList == null || resourceList.isEmpty()) {
            return java.util.Optional.empty();
        }
        if (resourceList.size() > 1) {
            throw new IllegalStateException("Resource port '" + portName + "' has " + resourceList.size() + " resources, but exactly one is required for optional resources.");
        }
        IResource resource = resourceList.get(0);
        if (!expectedType.isInstance(resource)) {
            throw new IllegalStateException("Resource at port '" + portName + "' is of type " + resource.getClass().getName() + ", but expected type is " + expectedType.getName());
        }
        return java.util.Optional.of(expectedType.cast(resource));
    }

    /**
     * Checks if a resource port has at least one resource configured.
     *
     * @param portName The name of the resource port.
     * @return {@code true} if the port has one or more resources, {@code false} otherwise.
     */
    protected boolean hasResource(String portName) {
        return resources.containsKey(portName) && !resources.get(portName).isEmpty();
    }

    /**
     * Records an operational error for tracking and monitoring.
     * <p>
     * <strong>IMPORTANT:</strong> Use this method ONLY for transient errors where the service
     * continues running. For fatal errors that require stopping the service, use
     * {@code log.error()} and throw an exception instead.
     * <p>
     * Use this method to track transient errors that don't require service termination
     * but may affect data quality or indicate problems. These errors affect the service's
     * health status ({@link #isHealthy()}).
     * <p>
     * The error collection is bounded by {@link #getMaxErrors()} to prevent unbounded
     * memory growth in long-running services. When the limit is exceeded, the oldest
     * errors are automatically removed.
     * <p>
     * <strong>Examples of transient errors:</strong>
     * <ul>
     *   <li>Failed to send a single message to a queue</li>
     *   <li>Energy distribution strategy failed for one tick</li>
     *   <li>Temporary I/O error reading a batch file</li>
     * </ul>
     * <p>
     * <strong>NOT for:</strong> Fatal errors, InterruptedException, or any error that causes
     * an exception to be thrown. See {@link #run()} for complete error handling guidelines.
     *
     * @param code    Error code for categorization (e.g., "SEND_ERROR", "TICK_FAILED")
     * @param message Human-readable error message
     * @param details Additional context about the error
     */
    protected void recordError(String code, String message, String details) {
        errors.add(new OperationalError(Instant.now(), code, message, details));
        
        // Prevent unbounded memory growth
        int maxErrors = getMaxErrors();
        while (errors.size() > maxErrors) {
            errors.pollFirst();
        }
    }

    @Override
    public List<OperationalError> getErrors() {
        return new ArrayList<>(errors);
    }

    @Override
    public void clearErrors() {
        errors.clear();
    }

    /**
     * Returns whether the service is healthy and producing correct data.
     * <p>
     * <strong>Default implementation semantics:</strong>
     * <ul>
     *   <li>ERROR state → always unhealthy (service is dead)</li>
     *   <li>Any error in collection → unhealthy (data quality compromised)</li>
     *   <li>No errors → healthy (data is trustworthy)</li>
     * </ul>
     * <p>
     * This strict definition ensures operators know when data may be incomplete.
     * Subclasses can override this for more specific health checks.
     *
     * @return {@code true} if service is running without any errors, {@code false} otherwise
     */
    @Override
    public boolean isHealthy() {
        // ERROR state is always unhealthy
        if (getCurrentState() == State.ERROR) return false;
        
        // Any error means data quality is compromised
        return errors.isEmpty();
    }

    /**
     * Returns metrics for this service.
     * <p>
     * This implementation returns base metrics tracked by all services,
     * then calls {@link #addCustomMetrics(Map)} to allow subclasses to add
     * service-specific metrics.
     * <p>
     * Base metrics included:
     * <ul>
     *   <li>error_count - number of errors in the error collection</li>
     * </ul>
     *
     * @return Map of metric names to their current values
     */
    @Override
    public final Map<String, Number> getMetrics() {
        Map<String, Number> metrics = getBaseMetrics();
        addCustomMetrics(metrics);
        return metrics;
    }

    /**
     * Returns the base metrics tracked by AbstractService.
     * <p>
     * Private helper method called only by getMetrics().
     * Subclasses should not access this directly - use addCustomMetrics() hook instead.
     *
     * @return Map containing base metrics
     */
    private Map<String, Number> getBaseMetrics() {
        Map<String, Number> metrics = new java.util.LinkedHashMap<>();
        metrics.put("error_count", errors.size());
        return metrics;
    }

    /**
     * Hook method for subclasses to add service-specific metrics.
     * <p>
     * The default implementation does nothing. Subclasses should override this method
     * to add their own metrics to the provided map.
     * <p>
     * <strong>IMPORTANT:</strong> Always call {@code super.addCustomMetrics(metrics)} first
     * to ensure parent class metrics are included. This is critical for multi-level
     * inheritance hierarchies.
     * <p>
     * Example:
     * <pre>
     * &#64;Override
     * protected void addCustomMetrics(Map&lt;String, Number&gt; metrics) {
     *     super.addCustomMetrics(metrics);  // Always call super first!
     *     
     *     metrics.put("ticks_processed", ticksProcessed.get());
     *     metrics.put("throughput_per_sec", calculateThroughput());
     * }
     * </pre>
     *
     * @param metrics Mutable map to add custom metrics to (already contains base metrics)
     */
    protected void addCustomMetrics(Map<String, Number> metrics) {
        // Default: no custom metrics
    }
}