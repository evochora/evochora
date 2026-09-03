package org.evochora.datapipeline;

import java.lang.reflect.Constructor;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.evochora.compiler.api.CompilationException;
import org.evochora.datapipeline.resume.ResumeException;
import org.evochora.datapipeline.api.memory.IMemoryEstimatable;
import org.evochora.datapipeline.api.services.ISimulationSource;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.datapipeline.api.resources.IContextualResource;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.IResourceInitializer;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.datapipeline.api.services.IServiceFactory;
import org.evochora.datapipeline.api.services.ResourceBinding;
import org.evochora.datapipeline.api.services.ServiceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Owns the life cycle of the pipeline's services and binds them to the resources they work with.
 * <p>
 * The manager is built from the {@code pipeline} section of a configuration. It instantiates every
 * declared resource once, builds one factory per declared service, and — unless auto-start is
 * switched off — starts the services named in the startup sequence. A service exists as an object
 * only from a successful {@link #startService(String)} until the next start of the same name;
 * before the first start there is a configured name and a factory, but no instance.
 * <p>
 * <strong>Resources outlive services.</strong> A start binds a service to the resources its
 * configuration names and, where a resource is contextual, hands the service a wrapper made for
 * that binding rather than the resource itself. Stopping a service leaves both the bindings and
 * those wrappers in place; only {@link #shutdown()} closes the resources. That is what makes a
 * stop/start cycle possible while the process keeps running, and it is also why a manager cannot be
 * used again after a shutdown: closed resources are not reopened.
 * <p>
 * <strong>Failure to start.</strong> A start that fails for a recognised reason — insufficient
 * memory, a program that does not compile, a checkpoint that cannot be resumed from, a rejected
 * configuration, a world too large for a Java array — is logged with an explanation and swallowed,
 * leaving the service without an instance and therefore reported as
 * {@link IService.State#STOPPED}. Any other runtime failure is logged and rethrown.
 * <p>
 * <strong>Failure while running.</strong> The manager does not watch its services. One that ends in
 * {@link IService.State#ERROR} stays registered in that state: it is skipped by
 * {@link #stopAll()}, it makes {@link #isHealthy()} false and shows up in
 * {@link #getServiceErrors()}, and it is replaced only by an explicit start of its name.
 * <p>
 * <strong>Threading.</strong> The registries are concurrent maps and the reporting methods take
 * their snapshots without locking, so they may be called from any thread while services run. The
 * life-cycle methods hold no lock either: two threads starting or stopping the same service at the
 * same time race, and serialising them is the caller's job. {@link #stopAll()} makes deliberate use
 * of this by stopping distinct services in parallel.
 */
public class ServiceManager implements IMonitorable {

    private static final Logger log = LoggerFactory.getLogger(ServiceManager.class);

    private final Config pipelineConfig;
    private final Map<String, IServiceFactory> serviceFactories = new ConcurrentHashMap<>();
    // Contains all service instances (RUNNING, PAUSED, STOPPED, ERROR)
    // Services are removed only on explicit restart or during shutdown
    private final Map<String, IService> services = new ConcurrentHashMap<>();
    private final Map<String, IResource> resources = new ConcurrentHashMap<>();
    private final Map<String, List<ResourceBinding>> serviceResourceBindings = new ConcurrentHashMap<>();
    private final List<String> startupSequence;
    private final Map<String, List<PendingBinding>> pendingBindingsMap = new ConcurrentHashMap<>();
    // Stores the wrapped resources currently being created for a service (used to coordinate between factory and bindings)
    private final Map<String, Map<String, List<IResource>>> activeWrappedResources = new ConcurrentHashMap<>();
    // Cached reference to the service that owns the current simulation run (set during startService)
    private volatile ISimulationSource simulationSource;
    /**
     * The simulation parameters read from the configured engine's options, or null when no engine
     * is configured. Used by the memory estimate when the engine does not run in this process.
     */
    private final SimulationParameters configuredSimulationParameters;

    /**
     * Builds the manager from a configuration and, unless told otherwise, brings the pipeline up.
     * <p>
     * In this order: the resource initializers declared in {@code init} blocks are run — before any
     * resource class is loaded, because some drivers read system properties at load time and each
     * initializer class runs only once; then every entry under {@code resources} is instantiated;
     * then a factory is built for every entry under {@code services}. A resource or a service whose
     * definition cannot be used is logged and skipped so that the rest of the pipeline still comes
     * up. A service whose factory could not be built — one naming a resource that does not exist,
     * for instance — is unknown to the manager from then on: it cannot be started and does not
     * appear among the reported statuses.
     * <p>
     * If {@code autoStart} is absent or true and a non-empty {@code startupSequence} is configured,
     * the services it names are started in that order before the constructor returns, and a memory
     * estimate for the running pipeline is logged afterwards. Otherwise nothing is started and the
     * services wait for {@link #startAll()} or {@link #startService(String)}.
     *
     * @param rootConfig configuration holding a {@code pipeline} section; nothing outside that
     *                   section is read
     * @throws IllegalArgumentException if the configuration has no {@code pipeline} section, or if
     *                                  the options of a configured simulation engine do not form
     *                                  valid simulation parameters — checked before anything is
     *                                  started, so that the failure leaves no service running
     * @throws RuntimeException if auto-starting a service fails for a reason the manager does not
     *                          recognise, in which case the failure is not swallowed but passed on
     */
    public ServiceManager(Config rootConfig) {
        this.pipelineConfig = loadPipelineConfig(rootConfig);
        log.info("Initializing ServiceManager...");

        // The engine's options are read and validated before any resource or service exists:
        // a configuration the estimate cannot be built on fails here, with nothing to stop.
        this.configuredSimulationParameters = extractSimulationParameters(this.pipelineConfig);

        // Run resource initializers BEFORE loading any resource classes
        // This allows initializers to set system properties that drivers read at load time
        runResourceInitializers(this.pipelineConfig);

        instantiateResources(this.pipelineConfig);
        buildServiceFactories(this.pipelineConfig);

        if (pipelineConfig.hasPath("startupSequence")) {
            this.startupSequence = pipelineConfig.getStringList("startupSequence");
        } else {
            this.startupSequence = Collections.emptyList();
        }

        log.info("ServiceManager initialized with {} resources and {} service factories.", resources.size(), serviceFactories.size());

        // Auto-start services if configured
        boolean autoStart = pipelineConfig.hasPath("autoStart")
                ? pipelineConfig.getBoolean("autoStart")
                : true; // default to true for production readiness

        if (autoStart && !this.startupSequence.isEmpty()) {
            log.info("\u001B[34m========== Service Startup ==========\u001B[0m");
            startAllInternal();
            
            // Perform memory estimation AFTER services are instantiated
            // This allows iterating over actual service instances
            performMemoryEstimation();
        } else if (!autoStart) {
            log.info("Auto-start is disabled. Services must be started manually via API.");
        } else {
            log.info("No startup sequence defined. Services must be started manually via API.");
        }
    }

    private Config loadPipelineConfig(Config rootConfig) {
        if (!rootConfig.hasPath("pipeline")) {
            throw new IllegalArgumentException("Configuration must contain 'pipeline' section");
        }
        return rootConfig.getConfig("pipeline");
    }

    /**
     * Runs resource initializers before any resource classes are loaded.
     * <p>
     * Initializers are used for early system-level configuration that must happen
     * before certain drivers are loaded (e.g., H2's temp directory must be set
     * before the H2 driver is loaded because H2 caches the value at load time).
     * <p>
     * The method:
     * <ol>
     *   <li>Scans all resource definitions for {@code init} blocks</li>
     *   <li>Deduplicates by {@code init.className} (each initializer runs once)</li>
     *   <li>Instantiates and runs each initializer</li>
     * </ol>
     *
     * @param config The pipeline configuration
     */
    private void runResourceInitializers(Config config) {
        if (!config.hasPath("resources")) {
            return;
        }

        Config resourcesConfig = config.getConfig("resources");
        
        // Collect and deduplicate initializers by className
        Map<String, Config> initializersByClass = new LinkedHashMap<>();
        
        for (String resourceName : resourcesConfig.root().keySet()) {
            try {
                Config resourceDefinition = resourcesConfig.getConfig(resourceName);
                if (resourceDefinition.hasPath("init")) {
                    Config initConfig = resourceDefinition.getConfig("init");
                    if (initConfig.hasPath("className")) {
                        String className = initConfig.getString("className");
                        // First one wins (for consistent behavior)
                        if (!initializersByClass.containsKey(className)) {
                            Config options = initConfig.hasPath("options") 
                                ? initConfig.getConfig("options") 
                                : ConfigFactory.empty();
                            initializersByClass.put(className, options);
                        }
                    }
                }
            } catch (Exception e) {
                // The failure can come from the resource definition itself or from the init block
                // inside it, so the message claims neither. What matters is the consequence: an
                // initializer that is not registered here never runs, and it runs for a reason —
                // to configure something before the class that reads it is loaded.
                log.error("Resource '{}' could not be examined for an init block: {}. If it declares an initializer, that initializer will not run.",
                        resourceName, e.getMessage());
            }
        }

        if (initializersByClass.isEmpty()) {
            return;
        }

        // Run each initializer once, collect successfully executed ones for summary log
        List<String> executedInitializers = new ArrayList<>();
        for (Map.Entry<String, Config> entry : initializersByClass.entrySet()) {
            String className = entry.getKey();
            Config options = entry.getValue();
            
            try {
                log.debug("Running resource initializer: {}", className);
                IResourceInitializer initializer = (IResourceInitializer) Class.forName(className)
                    .getDeclaredConstructor()
                    .newInstance();
                initializer.initialize(options);
                // Extract simple class name for readable log
                String simpleName = className.substring(className.lastIndexOf('.') + 1);
                executedInitializers.add(simpleName);
            } catch (ClassNotFoundException e) {
                log.error("Resource initializer class not found: {}", className);
            } catch (ClassCastException e) {
                log.error("Resource initializer '{}' does not implement IResourceInitializer", className);
            } catch (Exception e) {
                log.error("Failed to run resource initializer '{}': {}", className, e.getMessage());
            }
        }
        
        if (!executedInitializers.isEmpty()) {
            log.info("Resource initializers executed: {}", String.join(", ", executedInitializers));
        }
    }

    private void instantiateResources(Config config) {
        if (!config.hasPath("resources")) {
            log.debug("No resources configured.");
            return;
        }
        log.info("\u001B[34m========== Resource Initialization ==========\u001B[0m");
        Config resourcesConfig = config.getConfig("resources");
        for (String resourceName : resourcesConfig.root().keySet()) {
            try {
                Config resourceDefinition = resourcesConfig.getConfig(resourceName);
                String className = resourceDefinition.getString("className");
                Config options = resourceDefinition.hasPath("options")
                        ? resourceDefinition.getConfig("options")
                        : ConfigFactory.empty();

                IResource resource = (IResource) Class.forName(className)
                        .getConstructor(String.class, Config.class)
                        .newInstance(resourceName, options);
                resources.put(resourceName, resource);
                log.info("Instantiated resource '{}' of type {}", resourceName, className);
            } catch (Exception e) {
                // Extract root cause for clear error message (no stack trace)
                Throwable cause = e;
                while (cause.getCause() != null && cause.getCause() != cause) {
                    cause = cause.getCause();
                }
                String errorMsg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                log.error("Failed to instantiate resource '{}': {}. Skipping this resource.", resourceName, errorMsg);
            }
        }
    }

    private void buildServiceFactories(Config config) {
        if (!config.hasPath("services")) {
            log.debug("No services configured.");
            return;
        }
        log.info("\u001B[34m========== Service Initialization ==========\u001B[0m");
        Config servicesConfig = config.getConfig("services");
        for (String serviceName : servicesConfig.root().keySet()) {
            try {
                Config serviceDefinition = servicesConfig.getConfig(serviceName);
                String className = serviceDefinition.getString("className");
                Config options = serviceDefinition.hasPath("options") ? serviceDefinition.getConfig("options") : ConfigFactory.empty();

                List<PendingBinding> pendingBindings = new ArrayList<>();
                if (serviceDefinition.hasPath("resources")) {
                    Config resourcesConfig = serviceDefinition.getConfig("resources");
                    for (Map.Entry<String, com.typesafe.config.ConfigValue> entry : resourcesConfig.root().entrySet()) {
                        String portName = entry.getKey();
                        String resourceUri = entry.getValue().unwrapped().toString();
                        ResourceContext context = parseResourceUri(resourceUri, serviceName, portName);
                        IResource baseResource = resources.get(context.resourceName());
                        if (baseResource == null) {
                            throw new IllegalArgumentException(String.format("Service '%s' references unknown resource '%s' for port '%s'", serviceName, context.resourceName(), portName));
                        }
                        pendingBindings.add(new PendingBinding(context, baseResource));
                    }
                }
                pendingBindingsMap.put(serviceName, pendingBindings);

                final String factoryServiceName = serviceName;  // Effectively final for lambda
                Constructor<?> constructor = Class.forName(className)
                        .getConstructor(String.class, Config.class, Map.class);

                IServiceFactory factory = () -> {
                    try {
                        // Use wrapped resources from activeWrappedResources (populated by startService)
                        Map<String, List<IResource>> injectableResources = activeWrappedResources.get(factoryServiceName);
                        if (injectableResources == null) {
                            throw new IllegalStateException("No wrapped resources prepared for service: " + factoryServiceName);
                        }
                        return (IService) constructor.newInstance(factoryServiceName, options, injectableResources);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to create an instance of service '" + factoryServiceName + "'", e);
                    }
                };
                serviceFactories.put(serviceName, factory);
                log.info("Built factory for service '{}' of type {}", serviceName, className);
            } catch (Exception e) {
                // Extract root cause for clear error message (no stack trace)
                Throwable cause = e;
                while (cause.getCause() != null && cause.getCause() != cause) {
                    cause = cause.getCause();
                }
                String errorMsg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                log.error("Failed to build factory for service '{}': {}. Skipping this service.", serviceName, errorMsg);
            }
        }
    }

    /**
     * How far a chain of causes is followed. Far beyond any chain this code produces — the bound is
     * there so that a ring ends the walk, not to cut a real chain short.
     */
    private static final int MAX_CAUSE_DEPTH = 100;

    /**
     * The outermost failure of the given type along a chain of causes, or {@code null} if none is of
     * that type.
     * <p>
     * A failure raised during startup reaches this class wrapped in whatever reflection put around
     * it, so its type has to be sought rather than read off. Sought from the outside in, because a
     * failure that was given a reason on its way out carries the technical exception it replaced as
     * its own cause: taking the innermost link would find that technical exception again and lose
     * the reason.
     *
     * The walk is bounded. Java rejects a throwable given itself as its cause but not a ring of two,
     * which the public API can build — and every startup failure passes through here, including ones
     * raised inside libraries. A ring would not produce a wrong message but a node that neither
     * starts nor stops.
     *
     * @param <T> the type of failure looked for
     * @param thrown the failure as it arrived
     * @param type the type of failure looked for
     * @return the outermost link of that type, or {@code null}
     */
    static <T extends Throwable> T findInChain(Throwable thrown, Class<T> type) {
        Throwable link = thrown;
        for (int depth = 0; link != null && depth < MAX_CAUSE_DEPTH; depth++, link = link.getCause()) {
            if (type.isInstance(link)) {
                return type.cast(link);
            }
        }
        return null;
    }

    private ResourceContext parseResourceUri(String uri, String serviceName, String portName) {
        String[] mainParts = uri.split(":", 2);

        String usageType;
        String resourceAndParamsStr;

        if (mainParts.length == 2) {
            // Format: "usageType:resourceName?params"
            usageType = mainParts[0];
            resourceAndParamsStr = mainParts[1];
        } else {
            // Format: "resourceName?params" (non-contextual resource)
            usageType = null;
            resourceAndParamsStr = uri;
        }

        String[] resourceAndParams = resourceAndParamsStr.split("\\?", 2);
        String resourceName = resourceAndParams[0];
        Map<String, String> params = new HashMap<>();
        if (resourceAndParams.length > 1) {
            Arrays.stream(resourceAndParams[1].split("&"))
                  .map(p -> p.split("=", 2))
                  .filter(p -> p.length == 2)
                  .forEach(p -> params.put(p[0], p[1]));
        }
        return new ResourceContext(serviceName, portName, usageType, resourceName, Collections.unmodifiableMap(params));
    }

    private record PendingBinding(ResourceContext context, IResource baseResource) {}

    private void applyToAllServices(Consumer<String> action, List<String> serviceNames) {
        for (String serviceName : serviceNames) {
            try {
                action.accept(serviceName);
            } catch (IllegalStateException | IllegalArgumentException e) {
                log.warn("Could not perform action on service '{}': {}", serviceName, e.getMessage());
            }
        }
    }

    /**
     * Starts the services named in the configured startup sequence, in that order.
     * <p>
     * Nothing else is started: a configured service the sequence does not name stays untouched. A
     * service that is not defined, or that is already running and would have to be restarted
     * explicitly, is logged and skipped so the remaining ones still come up. Every other outcome of
     * a start is the one described for {@link #startService(String)}, including a failure that is
     * passed on and then leaves the rest of the sequence unstarted.
     */
    public void startAll() {
        log.info("\u001B[34m========== Starting Services ==========\u001B[0m");
        startAllInternal();
    }
    
    private void startAllInternal() {
        List<String> toStart = new ArrayList<>(startupSequence);
        //serviceFactories.keySet().stream().filter(s -> !toStart.contains(s)).forEach(toStart::add);
        applyToAllServices(this::startService, toStart);
    }

    /**
     * Stops every service that is currently running or paused and waits for all of them.
     * <p>
     * The candidates are the startup sequence in reverse, followed by any started service the
     * sequence does not name. Services that have already stopped themselves or ended in
     * {@link IService.State#ERROR} are left alone. Each of the remaining ones is stopped on a
     * thread of its own, so the reversed order decides only in which order the stops are triggered,
     * not in which order they take effect; the call returns once all those threads have finished.
     * If the calling thread is interrupted while waiting, the wait is given up and the interrupt
     * flag is restored, which can leave services still stopping in the background.
     * <p>
     * The services stay registered with the state they ended in, so they can still be inspected and
     * started again. Resources are left open; closing them is {@link #shutdown()}'s job.
     */
    public void stopAll() {
        log.info("\u001B[34m========== Stopping Services ==========\u001B[0m");
        // Reversed only to decide which stop is triggered first. The stops themselves run at the
        // same time (see below), so this order does not sequence them: a service later in the list
        // may well finish stopping before one earlier in it.
        List<String> toStop = new ArrayList<>(startupSequence);
        Collections.reverse(toStop);
        services.keySet().stream().filter(s -> !toStop.contains(s)).forEach(toStop::add);
        // Filter to only stop services that are in a stoppable state (RUNNING or PAUSED)
        // This excludes one-shot services that have already stopped themselves
        List<String> actuallyStoppable = toStop.stream()
                .filter(name -> {
                    IService service = services.get(name);
                    if (service == null) return false;
                    IService.State state = service.getCurrentState();
                    return state == IService.State.RUNNING || state == IService.State.PAUSED;
                })
                .collect(Collectors.toList());

        // Stop all services in parallel — each service.stop() blocks until its thread
        // terminates (or times out), so parallel execution reduces total shutdown time
        // from N × timeout to ~1 × timeout.
        List<Thread> stopThreads = new ArrayList<>();
        for (String name : actuallyStoppable) {
            Thread t = new Thread(() -> {
                try {
                    stopService(name);
                } catch (Exception e) {
                    log.warn("Could not stop service '{}': {}", name, e.getMessage());
                }
            }, "shutdown-" + name);
            t.start();
            stopThreads.add(t);
        }
        for (Thread t : stopThreads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // NOTE: Resources are NOT closed here to allow restart via HTTP API.
        // Resources are only closed during JVM shutdown via shutdown() method.
    }
    
    /**
     * Performs a complete shutdown: stops all services and closes all resources.
     * <p>
     * This method should only be called during JVM shutdown (via shutdown hook in Node),
     * NOT during normal stop/start cycling via HTTP API.
     * <p>
     * Once resources are closed, they cannot be reopened, making restart impossible.
     */
    public void shutdown() {
        stopAll();
        closeAllResources();
    }
    
    /**
     * Closes all resources to ensure clean shutdown.
     * <p>
     * This method is called after all services have been stopped to ensure that
     * resources (especially databases) are properly closed and data is flushed.
     * With DB_CLOSE_ON_EXIT=FALSE, H2 will not close automatically, so we must
     * explicitly close all resources here.
     * <p>
     * Resources that implement {@link AutoCloseable} (H2Database, H2TopicResource)
     * will close their own wrappers before shutting down connection pools.
     * Other resources (e.g., in-memory queues) do not require explicit shutdown.
     */
    private void closeAllResources() {
        log.info("\u001B[34m========== Closing Resource ==========\u001B[0m");
        
        for (Map.Entry<String, IResource> entry : resources.entrySet()) {
            String resourceName = entry.getKey();
            IResource resource = entry.getValue();
            
            if (resource instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) resource).close();
                    log.info("Closed resource: {}", resourceName);
                } catch (Exception e) {
                    log.error("Failed to close resource '{}': {}", resourceName, e.getMessage());
                }
            } else {
                log.debug("Resource '{}' does not implement AutoCloseable, skipping", resourceName);
            }
        }
    }

    /**
     * Pauses every registered service, that is, every service that has been started at least once
     * and not been replaced since.
     * <p>
     * What pausing means in a service's current state is the service's own decision. A service that
     * refuses is logged and skipped, and the remaining ones are still asked.
     */
    public void pauseAll() {
        log.info("Pausing all services...");
        applyToAllServices(this::pauseService, new ArrayList<>(services.keySet()));
    }

    /**
     * Resumes every registered service, that is, every service that has been started at least once
     * and not been replaced since.
     * <p>
     * What resuming means in a service's current state is the service's own decision. A service that
     * refuses is logged and skipped, and the remaining ones are still asked.
     */
    public void resumeAll() {
        log.info("Resuming all services...");
        applyToAllServices(this::resumeService, new ArrayList<>(services.keySet()));
    }

    /**
     * Stops every service that is running or paused and starts that same set again.
     * <p>
     * The set is taken before the stop and holds every registered service in one of those states,
     * not only the ones the startup sequence names, so a service switched on outside the sequence
     * comes back as well. Services the sequence names are started in its order; the rest follow.
     * <p>
     * Every service that comes back is a new instance with newly bound resources, because a start
     * discards the stopped one — a service that was paused therefore returns running. The resources
     * themselves are neither closed nor reopened.
     */
    public void restartAll() {
        log.info("Restarting all services...");
        // Taken before the stop, because stopping is what makes this information unavailable.
        // Starting the startup sequence instead would drop every service that was switched on
        // outside it, and drop it silently: the stop reaches every running service, the sequence
        // does not. Services named in the sequence keep its order; the rest follow.
        List<String> running = services.entrySet().stream()
                .filter(e -> {
                    IService.State state = e.getValue().getCurrentState();
                    return state == IService.State.RUNNING || state == IService.State.PAUSED;
                })
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        List<String> toRestart = startupSequence.stream()
                .filter(running::contains)
                .collect(Collectors.toCollection(ArrayList::new));
        running.stream().filter(name -> !toRestart.contains(name)).forEach(toRestart::add);

        stopAll();

        log.info("\u001B[34m========== Starting Services ==========\u001B[0m");
        applyToAllServices(this::startService, toRestart);
    }

    /**
     * Registers a custom factory for a service, overriding the config-based factory.
     * <p>
     * This is useful for resume mode where a service needs to be created with
     * pre-existing state rather than fresh from configuration.
     * <p>
     * <strong>Important:</strong> This method must be called BEFORE {@link #startAll()}
     * or {@link #startService(String)} for the custom factory to take effect.
     *
     * @param serviceName The name of the service to override
     * @param factory The custom factory that will create the service instance
     * @throws IllegalArgumentException if the serviceName is not defined in config
     */
    public void registerCustomFactory(String serviceName, IServiceFactory factory) {
        if (!serviceFactories.containsKey(serviceName)) {
            throw new IllegalArgumentException(
                "Cannot register custom factory for unknown service: " + serviceName +
                ". The service must be defined in the pipeline configuration.");
        }
        serviceFactories.put(serviceName, factory);
        log.info("Registered custom factory for service '{}'", serviceName);
    }

    /**
     * Creates a fresh instance of the named service, binds its resources and starts it.
     * <p>
     * An instance of that name that is still registered in {@link IService.State#STOPPED} or
     * {@link IService.State#ERROR} is discarded together with its resource bindings and replaced. A
     * running or paused instance is not replaced; {@link #restartService(String)} is the way to do
     * that.
     * <p>
     * Resources are prepared before the service is constructed. For each port its configuration
     * names, a contextual resource is asked for a wrapper made for that binding, and it is the
     * wrapper — not the resource behind it — that the service receives and that its binding records;
     * a resource that is not contextual is passed through as it is. Every start asks for new
     * wrappers and nothing here hands them back: they are released when the resource behind them is
     * closed, which happens in {@link #shutdown()}. Repeated starts of one service therefore leave
     * one set of wrappers behind per start.
     * <p>
     * A service that identifies itself as the source of the simulation run becomes the one the
     * manager reports through {@link #getActiveRunId()}.
     * <p>
     * Most failures to start are not passed on. Insufficient memory, a program that does not
     * compile, a checkpoint that cannot be resumed from, a rejected configuration and a world too
     * large for a Java array are logged with an explanation, and so is any checked exception; in
     * each case the service is left without an instance and is reported as
     * {@link IService.State#STOPPED} afterwards. Every other {@link RuntimeException} is logged and
     * rethrown.
     *
     * @param name the configured name of the service
     * @throws IllegalArgumentException if no service of that name is defined
     * @throws IllegalStateException if an instance of that name is still running or paused
     */
    public void startService(String name) {
        // VALIDATION: Check if the service already exists and its state
        IService existing = services.get(name);
        if (existing != null) {
            IService.State state = existing.getCurrentState();
            // Services that have finished (STOPPED/ERROR) can be restarted
            if (state == IService.State.STOPPED || state == IService.State.ERROR) {
                log.debug("Removing previous instance of service '{}' (state: {}) before creating new instance", name, state);
                if (existing == simulationSource) {
                    simulationSource = null;
                }
                services.remove(name);
                serviceResourceBindings.remove(name);
            } else {
                // Service is still RUNNING or PAUSED
                throw new IllegalStateException("Service '" + name + "' is already running (state: " + state + "). Use restartService() for an explicit restart.");
            }
        }

        IServiceFactory factory = serviceFactories.get(name);
        if (factory == null) {
            throw new IllegalArgumentException("Service '" + name + "' is not defined.");
        }

        try {
            log.debug("Creating a new instance for service '{}'.", name);

            // Step 1: Create wrapped resources ONCE and store them for both injection and bindings
            List<PendingBinding> pendingBindings = pendingBindingsMap.getOrDefault(name, Collections.emptyList());
            Map<String, List<IResource>> wrappedResourcesMap = new HashMap<>();
            Map<ResourceContext, IResource> contextToWrappedResource = new HashMap<>();

            for (PendingBinding pb : pendingBindings) {
                IResource wrappedResource = (pb.baseResource() instanceof IContextualResource)
                        ? ((IContextualResource) pb.baseResource()).getWrappedResource(pb.context())
                        : pb.baseResource();
                wrappedResourcesMap.computeIfAbsent(pb.context().portName(), k -> new ArrayList<>()).add(wrappedResource);
                contextToWrappedResource.put(pb.context(), wrappedResource);
            }

            // Step 2: Store wrapped resources for factory to use
            activeWrappedResources.put(name, wrappedResourcesMap);

            try {
                // Step 3: Create service instance (factory will use wrapped resources from activeWrappedResources)
                IService newServiceInstance = factory.create();

                // Step 4: Create ResourceBindings using the SAME wrapped resource instances
                List<ResourceBinding> finalBindings = pendingBindings.stream()
                        .map(pb -> new ResourceBinding(pb.context(), newServiceInstance, contextToWrappedResource.get(pb.context())))
                        .collect(Collectors.toList());
                serviceResourceBindings.put(name, Collections.unmodifiableList(finalBindings));

                services.put(name, newServiceInstance);
                if (newServiceInstance instanceof ISimulationSource source) {
                    simulationSource = source;
                }

                newServiceInstance.start();
            } finally {
                // Step 5: Clean up temporary map
                activeWrappedResources.remove(name);
            }
        } catch (OutOfMemoryError e) {
            // Clean up maps in case of a startup failure.
            clearSimulationSourceIfMatch(services.get(name));
            services.remove(name);
            serviceResourceBindings.remove(name);
            activeWrappedResources.remove(name);

            // Provide friendly, actionable error message with memory calculation
            String errorMsg = "Failed to start service '" + name + "': Insufficient memory.";

            // Try to calculate memory requirements for simulation-engine
            if (name.equals("simulation-engine") && pipelineConfig.hasPath("services.simulation-engine.options.environment.shape")) {
                try {
                    List<Integer> shape = pipelineConfig.getIntList("services.simulation-engine.options.environment.shape");
                    long totalCells = shape.stream().mapToLong(Integer::longValue).reduce(1L, (a, b) -> a * b);
                    long estimatedMemoryGB = ((totalCells * 8) / (1024 * 1024 * 1024)) + 4; // 8 bytes per cell + 4GB overhead
                    errorMsg += " World size " + shape + " requires ~" + estimatedMemoryGB + " GB. Increase heap with -Xmx" + estimatedMemoryGB + "g or reduce world size.";
                } catch (Exception ex) {
                    errorMsg += " Increase heap size with -Xmx16g or reduce world size in configuration.";
                }
            } else {
                errorMsg += " Increase heap size with -Xmx16g";
            }

            log.error(errorMsg);
            // Don't throw - just log and return. Service remains in stopped state.
            return;
        } catch (RuntimeException e) {
            // Clean up maps in case of a startup failure.
            clearSimulationSourceIfMatch(services.get(name));
            services.remove(name);
            serviceResourceBindings.remove(name);
            activeWrappedResources.remove(name);

            // Unwrap reflection exceptions to find root cause
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }

            // Sought along the chain rather than taken from its end: a resume failure explains what
            // about the checkpoint is unusable, and explaining it is what gives it a cause of its
            // own — which the unwrapping above would then run past.
            ResumeException resumeFailure = findInChain(e, ResumeException.class);

            // Check if this is an OutOfMemoryError wrapped in RuntimeException (from reflection)
            if (cause instanceof OutOfMemoryError && name.equals("simulation-engine") && pipelineConfig.hasPath("services.simulation-engine.options.environment.shape")) {
                try {
                    List<Integer> shape = pipelineConfig.getIntList("services.simulation-engine.options.environment.shape");
                    long totalCells = shape.stream().mapToLong(Integer::longValue).reduce(1L, (a, b) -> a * b);
                    long estimatedMemoryGB = ((totalCells * 8) / (1024 * 1024 * 1024)) + 4; // 8 bytes per cell + 4GB overhead
                    String errorMsg = "Failed to start service '" + name + "': Insufficient memory. World size " + shape +
                        " requires ~" + estimatedMemoryGB + " GB. Increase heap with -Xmx" + estimatedMemoryGB + "g or reduce world size.";
                    log.error(errorMsg);
                    return;
                } catch (Exception ex) {
                    log.error("Failed to start service '{}': Insufficient memory. Increase heap size with -Xmx16g or reduce world size.", name);
                    return;
                }
            }

            // Check if this is a compilation error (CompilationException contains formatted error messages)
            if (cause instanceof CompilationException) {
                log.error("Compilation failed for service '{}':\n{}", name, cause.getMessage());
                // Don't throw - just log and return. Service remains in stopped state.
                return;
            }

            // Before the configuration check below, and not by coincidence: a checkpoint that cannot
            // be read reports the argument it could not make sense of, so its cause is often an
            // IllegalArgumentException. Left in the other order, that cause would be read as a
            // configuration error and send the operator to the configuration file.
            if (resumeFailure != null) {
                String errorMsg = "Resume failed for service '" + name + "': " + resumeFailure.getMessage();
                log.error(errorMsg);
                // Don't throw - just log and return. Service remains in stopped state.
                return;
            }

            // Check if this is a configuration error (IllegalArgumentException, ConfigException, or
            // NegativeArraySizeException)
            if (cause instanceof IllegalArgumentException || cause instanceof com.typesafe.config.ConfigException) {
                String errorMsg = "Configuration error for service '" + name + "': " + cause.getMessage();
                log.error(errorMsg);
                // Don't throw - just log and return. Service remains in stopped state.
                return;
            }

            if (cause instanceof NegativeArraySizeException && name.equals("simulation-engine") && pipelineConfig.hasPath("services.simulation-engine.options.environment.shape")) {
                try {
                    List<Integer> shape = pipelineConfig.getIntList("services.simulation-engine.options.environment.shape");
                    long totalCells = shape.stream().mapToLong(Integer::longValue).reduce(1L, (a, b) -> a * b);
                    String errorMsg = "Configuration error for service '" + name + "': World size " + shape +
                        " is too large (" + String.format("%,d", totalCells) + " cells). Java arrays are limited to " +
                        String.format("%,d", Integer.MAX_VALUE) + " elements. Reduce world dimensions.";
                    log.error(errorMsg);
                    return;
                } catch (Exception ex) {
                    log.error("Configuration error for service '{}': World dimensions cause integer overflow. Reduce world size.", name);
                    return;
                }
            }

            // Re-throw other runtime exceptions
            log.error("Failed to create and start a new instance for service '{}'.", name, e);
            throw e;
        }
    }

    /**
     * Stops the named service and waits for it to come to a halt.
     * <p>
     * A name with no registered instance — never started, or already discarded — is logged as a
     * warning and otherwise ignored, which is what lets a stop be asked for without knowing whether
     * anything is running. Otherwise the service's own {@code stop()} is called, which blocks until
     * the service has ended or waiting for it has been given up, and the state it ends in is
     * logged: {@link IService.State#ERROR} as a warning and any state other than that or
     * {@link IService.State#STOPPED} as an error. Neither is acted upon here.
     * <p>
     * The instance stays registered with that final state so it can still be inspected; the next
     * {@link #startService(String)} for the same name discards it. Its resource bindings stay in
     * place and the resource wrappers created for it are not released — that happens only when the
     * resources are closed in {@link #shutdown()}.
     *
     * @param name the configured name of the service
     */
    public void stopService(String name) {
        IService service = services.get(name);
        if (service == null) {
            log.warn("Attempted to stop service '{}', but it was not found among services.", name);
            return;
        }
        
        log.info("Stopping service '{}'...", name);
        service.stop();  // Already blocks until thread terminates (max 5 sec)
        
        // Check final state and log
        IService.State finalState = service.getCurrentState();
        if (finalState == IService.State.STOPPED) {
            log.debug("Service '{}' stopped successfully.", name);
        } else if (finalState == IService.State.ERROR) {
            log.warn("Service '{}' stopped with ERROR state.", name);
        } else {
            log.error("Service '{}' in unexpected state after stop(): {}", name, finalState);
        }
        
        // NOTE: Service remains in 'services' map with State STOPPED for monitoring.
        // It will be removed on next startService() or during shutdown().
    }

    /**
     * Pauses the named service.
     * <p>
     * What pausing means, and whether it is allowed in the service's current state, is the service's
     * own decision; this method only forwards the call and reports nothing back.
     *
     * @param serviceName the configured name of the service
     * @throws IllegalArgumentException if no instance of that name is registered, which is also the
     *                                  case for a configured service that has never been started
     */
    public void pauseService(String serviceName) {
        getServiceOrFail(serviceName).pause();
    }

    /**
     * Resumes the named service.
     * <p>
     * What resuming means, and whether it is allowed in the service's current state, is the
     * service's own decision; this method only forwards the call and reports nothing back.
     *
     * @param serviceName the configured name of the service
     * @throws IllegalArgumentException if no instance of that name is registered, which is also the
     *                                  case for a configured service that has never been started
     */
    public void resumeService(String serviceName) {
        getServiceOrFail(serviceName).resume();
    }

    /**
     * Stops the named service and starts it again as a fresh instance.
     * <p>
     * The stop half tolerates a service that has no instance, so this also serves to start a
     * configured service for the first time. The new instance is built by the factory and bound to
     * newly wrapped resources, while the resources themselves stay as they are. Both halves behave
     * as described for {@link #stopService(String)} and {@link #startService(String)}, which
     * includes swallowing a recognised start failure and leaving the service without an instance.
     *
     * @param serviceName the configured name of the service
     * @throws IllegalArgumentException if no service of that name is defined
     * @throws IllegalStateException if the service did not come to a halt and is still running or
     *                               paused when the start is attempted
     */
    public void restartService(String serviceName) {
        log.info("Restarting service '{}'...", serviceName);
        stopService(serviceName);
        startService(serviceName);
    }

    private IService getServiceOrFail(String serviceName) {
        IService service = services.get(serviceName);
        if (service == null) {
            throw new IllegalArgumentException("Service not found: " + serviceName);
        }
        return service;
    }

    /**
     * The service instances the manager holds, in no particular order.
     * <p>
     * This is an unmodifiable view of the live registry rather than a copy, so instances started or
     * replaced later show up in it. It holds every service that has been started at least once, in
     * whatever state it has reached — stopped and failed ones included — and nothing for a
     * configured service that has never been started. {@link #getAllServiceStatus()} covers those
     * as well.
     *
     * @return an unmodifiable view of the registered service instances
     */
    public Collection<IService> getAllServices() {
        return Collections.unmodifiableCollection(services.values());
    }

    @Override
    public Map<String, Number> getMetrics() {
        Map<String, Number> metrics = new HashMap<>();
        Map<IService.State, Long> serviceStates = services.values().stream()
                .collect(Collectors.groupingBy(IService::getCurrentState, Collectors.counting()));

        long stoppedCount = serviceFactories.size() - services.size();

        metrics.put("services_total", (long) serviceFactories.size());
        metrics.put("services_running", serviceStates.getOrDefault(IService.State.RUNNING, 0L));
        metrics.put("services_paused", serviceStates.getOrDefault(IService.State.PAUSED, 0L));
        metrics.put("services_stopped", serviceStates.getOrDefault(IService.State.STOPPED, 0L) + stoppedCount);
        metrics.put("services_error", serviceStates.getOrDefault(IService.State.ERROR, 0L));

        Map<IResource.UsageState, Long> resourceStates = serviceResourceBindings.values().stream()
                .flatMap(List::stream)
                .map(binding -> binding.resource().getUsageState(binding.context().usageType()))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        metrics.put("resources_total", resources.size());
        metrics.put("resources_active", resourceStates.getOrDefault(IResource.UsageState.ACTIVE, 0L));
        metrics.put("resources_waiting", resourceStates.getOrDefault(IResource.UsageState.WAITING, 0L));
        metrics.put("resources_failed", resourceStates.getOrDefault(IResource.UsageState.FAILED, 0L));

        return Collections.unmodifiableMap(metrics);
    }

    @Override
    public List<OperationalError> getErrors() {
        return services.values().stream()
                .filter(s -> s instanceof IMonitorable)
                .flatMap(s -> ((IMonitorable) s).getErrors().stream())
                .collect(Collectors.toList());
    }

    @Override
    public void clearErrors() {
        services.values().stream()
                .filter(s -> s instanceof IMonitorable)
                .forEach(s -> ((IMonitorable) s).clearErrors());
        log.info("Cleared errors for all monitorable services.");
    }

    @Override
    public boolean isHealthy() {
        boolean servicesOk = services.values().stream().noneMatch(s -> s.getCurrentState() == IService.State.ERROR);
        boolean resourcesOk = serviceResourceBindings.values().stream()
                .flatMap(List::stream)
                .noneMatch(b -> b.resource().getUsageState(b.context().usageType()) == IResource.UsageState.FAILED);
        return servicesOk && resourcesOk;
    }

    /**
     * The operational errors reported by the registered services, keyed by service name.
     * <p>
     * Only services that can be monitored and that actually have errors appear: a service with an
     * empty error list is left out, as is one that has never been started. The errors are the ones
     * accumulated since the service last had them cleared, which {@link #clearErrors()} does for
     * all of them at once.
     *
     * @return a map from service name to that service's errors, holding no empty lists
     */
    public Map<String, List<OperationalError>> getServiceErrors() {
        return services.entrySet().stream()
                .filter(e -> e.getValue() instanceof IMonitorable)
                .map(e -> new AbstractMap.SimpleEntry<>(e.getKey(), ((IMonitorable) e.getValue()).getErrors()))
                .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * The operational errors reported by one service.
     * <p>
     * A service that cannot be monitored has no errors to report and is not distinguished here from
     * one that reports none.
     *
     * @param serviceName the configured name of the service
     * @return that service's errors, or an empty list if it has none or cannot be monitored
     * @throws IllegalArgumentException if no instance of that name is registered, which is also the
     *                                  case for a configured service that has never been started
     */
    public List<OperationalError> getServiceErrors(String serviceName) {
        IService service = getServiceOrFail(serviceName);
        if (service instanceof IMonitorable) {
            return ((IMonitorable) service).getErrors();
        }
        return Collections.emptyList();
    }

    /**
     * A status for every configured service, whether or not it is running.
     * <p>
     * A service without an instance — never started, or discarded after a failed start — is reported
     * as {@link IService.State#STOPPED} with no metrics, errors or resource bindings. The statuses
     * are taken one after another, so they need not describe the same instant.
     *
     * @return a map from service name to that service's current status
     */
    public Map<String, ServiceStatus> getAllServiceStatus() {
        return serviceFactories.keySet().stream()
                .collect(Collectors.toMap(Function.identity(), this::getServiceStatus, (v1, v2) -> v1, LinkedHashMap::new));
    }

    /**
     * The resources the manager instantiated, keyed by their configured name.
     * <p>
     * A resource whose instantiation failed is absent, which is the only way this set differs from
     * the configured one; it is fixed once the manager is built. The map is an unmodifiable view
     * holding the shared instances the services work with, not copies, so a caller may read their
     * state but must not close them — their life cycle belongs to the manager.
     *
     * @return an unmodifiable view of the instantiated resources
     */
    public Map<String, IResource> getAllResourceStatus() {
        return Collections.unmodifiableMap(resources);
    }

    /**
     * Gets a resource with type-safe access.
     * <p>
     * Allows cross-process resource access (e.g., HttpServerProcess accessing
     * database resources created for pipeline services).
     * 
     * @param name Resource name from configuration
     * @param expectedType Expected type of the resource
     * @param <T> Resource type
     * @return The resource instance, cast to expected type
     * @throws IllegalArgumentException if resource not found or wrong type
     */
    public <T> T getResource(String name, Class<T> expectedType) {
        IResource resource = resources.get(name);

        if (resource == null) {
            throw new IllegalArgumentException(
                "Resource '" + name + "' not found. Available resources: " + resources.keySet()
            );
        }

        if (!expectedType.isInstance(resource)) {
            throw new IllegalArgumentException(
                "Resource '" + name + "' is " + resource.getClass().getName() +
                " but expected " + expectedType.getName()
            );
        }

        return expectedType.cast(resource);
    }

    /**
     * The current status of one configured service.
     * <p>
     * A configured service without an instance — never started, or discarded after a failed start —
     * is reported as {@link IService.State#STOPPED} with no metrics, errors or resource bindings.
     * For an existing instance, health is what the service reports about itself if it can be
     * monitored, and otherwise follows from its state alone: anything but
     * {@link IService.State#ERROR} counts as healthy.
     *
     * @param serviceName the configured name of the service
     * @return a snapshot of the service's state, health, metrics, errors and resource bindings
     * @throws IllegalArgumentException if no service of that name is defined in the configuration,
     *                                  which unlike the life-cycle methods is decided by the
     *                                  configuration and not by whether an instance exists
     */
    public ServiceStatus getServiceStatus(String serviceName) {
        if (!serviceFactories.containsKey(serviceName)) {
            throw new IllegalArgumentException("Service not found: " + serviceName);
        }

        IService service = services.get(serviceName);
        if (service == null) {
            return new ServiceStatus(IService.State.STOPPED, true, Collections.emptyMap(), Collections.emptyList(), Collections.emptyList());
        }

        List<ResourceBinding> resourceBindings = serviceResourceBindings.getOrDefault(serviceName, Collections.emptyList());
        Map<String, Number> serviceMetrics = (service instanceof IMonitorable) ? ((IMonitorable) service).getMetrics() : Collections.emptyMap();
        List<OperationalError> errors = (service instanceof IMonitorable) ? ((IMonitorable) service).getErrors() : Collections.emptyList();
        boolean healthy = (service instanceof IMonitorable) ? ((IMonitorable) service).isHealthy() : (service.getCurrentState() != IService.State.ERROR);

        return new ServiceStatus(
                service.getCurrentState(),
                healthy,
                serviceMetrics,
                errors,
                resourceBindings
        );
    }
    
    // ==================== Memory Estimation ====================
    
    /**
     * Performs worst-case memory estimation for all configured components.
     * <p>
     * Collects estimates from all resources AND services that implement
     * {@link IMemoryEstimatable} and compares the total against available heap.
     * <p>
     * <strong>IMPORTANT:</strong> This method must be called AFTER services are
     * instantiated (after startAllInternal) to access service instances.
     * <p>
     * <strong>Output:</strong>
     * <ul>
     *   <li>INFO log if estimated memory fits within -Xmx</li>
     *   <li>WARN log if estimated memory exceeds -Xmx</li>
     *   <li>DEBUG log with per-component breakdown</li>
     * </ul>
     * <p>
     * <strong>Worst-Case Assumptions:</strong>
     * <ul>
     *   <li>100% environment occupancy (all cells filled)</li>
     *   <li>Maximum configured organisms alive simultaneously</li>
     *   <li>All queues and buffers at full capacity</li>
     * </ul>
     *
     */
    private void performMemoryEstimation() {
        // Prefer runtime parameters from a started service (correct for both new and resume mode)
        ISimulationSource source = simulationSource;
        SimulationParameters params = source != null ? source.getMemoryEstimationParameters() : null;

        // Without a local engine (it runs in another process), the parameters read from the
        // configuration at construction describe the world this node's services serve
        if (params == null) {
            params = configuredSimulationParameters;
        }

        if (params == null) {
            log.debug("Memory estimation skipped: simulation-engine not configured");
            return;
        }

        // Fixed overhead (independent of simulation parameters)
        long jvmOverhead = 300L * 1024 * 1024; // ~300 MB baseline
        // - HttpServerProcess: Jetty thread pool (mostly idle, stack-only) + request buffers = ~100 MB
        // - H2ConsoleProcess: Small embedded HTTP server = ~20 MB
        // - H2TcpServerProcess: TCP server for DB connections = ~30 MB
        long nodeProcessOverhead = 150L * 1024 * 1024; // 150 MB for Node processes
        long fixedOverhead = jvmOverhead + nodeProcessOverhead;

        // 1. Worst-case estimation (100% cell occupancy) — used for WARN threshold
        List<MemoryEstimate> worstCaseEstimates = collectEstimates(params);
        worstCaseEstimates.add(new MemoryEstimate(
            "JVM-overhead", jvmOverhead,
            "Thread stacks, class metadata, GC overhead, native memory",
            MemoryEstimate.Category.JVM_OVERHEAD
        ));
        long worstCaseTotal = worstCaseEstimates.stream().mapToLong(MemoryEstimate::estimatedBytes).sum()
                            + nodeProcessOverhead;

        // 2. Expected-peak estimation (25% cell occupancy)
        SimulationParameters expectedParams = params.withCellOccupancy(
            SimulationParameters.DEFAULT_EXPECTED_CELL_OCCUPANCY);
        long expectedComponentTotal = collectEstimates(expectedParams).stream()
            .mapToLong(MemoryEstimate::estimatedBytes).sum();
        long expectedTotal = expectedComponentTotal + fixedOverhead;

        long maxHeapBytes = Runtime.getRuntime().maxMemory();

        // Log detailed worst-case breakdown at DEBUG level
        Map<MemoryEstimate.Category, Long> categoryTotals = new EnumMap<>(MemoryEstimate.Category.class);
        log.debug("Memory estimation breakdown (WORST-CASE: 100% environment, {} max organisms):", params.maxOrganisms());
        for (MemoryEstimate estimate : worstCaseEstimates) {
            log.debug("  {} → {}: {}", estimate.componentName(), estimate.formattedBytes(), estimate.explanation());
            categoryTotals.merge(estimate.category(), estimate.estimatedBytes(), Long::sum);
        }
        log.debug("  Node processes (fixed overhead) → {}", SimulationParameters.formatBytes(nodeProcessOverhead));

        log.debug("Category totals:");
        for (Map.Entry<MemoryEstimate.Category, Long> entry : categoryTotals.entrySet()) {
            log.debug("  {}: {}", entry.getKey().getDisplayName(), SimulationParameters.formatBytes(entry.getValue()));
        }
        log.debug("  Node processes: {}", SimulationParameters.formatBytes(nodeProcessOverhead));

        // Always log expected peak at INFO level
        log.info("Memory estimate: {} expected peak ({}% occupancy), {} worst-case ceiling (100%), heap: {} (-Xmx)",
            SimulationParameters.formatBytes(expectedTotal),
            (int) (SimulationParameters.DEFAULT_EXPECTED_CELL_OCCUPANCY * 100),
            SimulationParameters.formatBytes(worstCaseTotal),
            SimulationParameters.formatBytes(maxHeapBytes));

        // WARN if worst-case exceeds available heap
        if (worstCaseTotal > maxHeapBytes) {
            long recommendedHeapBytes = (long) (worstCaseTotal * 1.3);

            String topOffenders = worstCaseEstimates.stream()
                .sorted((a, b) -> Long.compare(b.estimatedBytes(), a.estimatedBytes()))
                .limit(3)
                .map(e -> e.componentName() + " " + e.formattedBytes())
                .collect(Collectors.joining(", "));

            log.warn("Worst-case estimate {} exceeds heap {}. Largest: {}. Recommended: -Xmx{}",
                SimulationParameters.formatBytes(worstCaseTotal),
                SimulationParameters.formatBytes(maxHeapBytes),
                topOffenders,
                formatHeapRecommendation(recommendedHeapBytes));
        }
    }

    /**
     * Collects memory estimates from all {@link IMemoryEstimatable} resources and services.
     *
     * @param params Simulation parameters for estimation (may use reduced occupancy).
     * @return Mutable list of estimates from all components.
     */
    private List<MemoryEstimate> collectEstimates(SimulationParameters params) {
        List<MemoryEstimate> estimates = new ArrayList<>();
        for (IResource resource : resources.values()) {
            if (resource instanceof IMemoryEstimatable estimatable) {
                estimates.addAll(estimatable.estimateWorstCaseMemory(params));
            }
        }
        for (IService service : services.values()) {
            if (service instanceof IMemoryEstimatable estimatable) {
                estimates.addAll(estimatable.estimateWorstCaseMemory(params));
            }
        }
        return estimates;
    }
    
    /**
     * Returns the run ID of the currently active simulation, or {@code null} if no
     * simulation source service is running.
     *
     * @return the active run ID, or null
     */
    public String getActiveRunId() {
        ISimulationSource source = simulationSource;
        return source != null ? source.getRunId() : null;
    }

    private void clearSimulationSourceIfMatch(IService service) {
        if (service != null && service == simulationSource) {
            simulationSource = null;
        }
    }

    /**
     * Extracts simulation parameters from pipeline configuration by scanning for a SimulationEngine.
     * <p>
     * This is the fallback path used when no {@link ISimulationSource} service is available
     * (e.g., in distributed deployments where SimulationEngine runs in a separate process).
     * <p>
     * Searches all configured services for a SimulationEngine class and extracts
     * environment shape from its configuration. This approach works regardless of
     * the service name used in the configuration.
     *
     * A malformed engine configuration is not tolerated here: the estimate built from these
     * parameters decides whether the run fits into the heap, so an invalid value fails the start
     * instead of silently disabling the estimate.
     *
     * @param config Pipeline configuration
     * @return SimulationParameters or null if no SimulationEngine service is configured
     * @throws IllegalArgumentException if the engine's options do not form valid parameters
     * @throws com.typesafe.config.ConfigException if an option has the wrong type
     */
    private SimulationParameters extractSimulationParameters(Config config) {
        if (!config.hasPath("services")) {
            return null;
        }

        Config servicesConfig = config.getConfig("services");

        // Iterate over all configured services to find SimulationEngine
        for (String serviceName : servicesConfig.root().keySet()) {
            Config serviceConfig = servicesConfig.getConfig(serviceName);

            if (!serviceConfig.hasPath("className")) {
                continue;
            }

            String className = serviceConfig.getString("className");

            // Check if this is a SimulationEngine (by class name suffix)
            if (className.endsWith(".SimulationEngine") &&
                serviceConfig.hasPath("options.environment.shape")) {

                // Extract environment shape
                List<Integer> shapeList = serviceConfig.getIntList("options.environment.shape");
                int[] shape = shapeList.stream().mapToInt(Integer::intValue).toArray();

                // Calculate total cells in long; a product beyond long is a shape no estimate can price
                long totalCells = 1L;
                for (int dim : shape) {
                    try {
                        totalCells = Math.multiplyExact(totalCells, dim);
                    } catch (ArithmeticException overflow) {
                        throw new IllegalArgumentException("environment.shape " + Arrays.toString(shape)
                                + " holds more cells than a long can count", overflow);
                    }
                }

                // Derive maxOrganisms from environment size and density factor
                double organismDensityFactor = serviceConfig.hasPath("options.organismDensityFactor")
                    ? serviceConfig.getDouble("options.organismDensityFactor")
                    : SimulationParameters.DEFAULT_ORGANISM_DENSITY_FACTOR;
                int maxOrganisms = Math.max(1, (int) (totalCells * organismDensityFactor));

                // Read delta compression parameters
                int samplingInterval = serviceConfig.hasPath("options.samplingInterval")
                    ? serviceConfig.getInt("options.samplingInterval")
                    : SimulationParameters.DEFAULT_SAMPLING_INTERVAL;
                int accumulatedDeltaInterval = serviceConfig.hasPath("options.accumulatedDeltaInterval")
                    ? serviceConfig.getInt("options.accumulatedDeltaInterval")
                    : SimulationParameters.DEFAULT_ACCUMULATED_DELTA_INTERVAL;
                int snapshotInterval = serviceConfig.hasPath("options.snapshotInterval")
                    ? serviceConfig.getInt("options.snapshotInterval")
                    : SimulationParameters.DEFAULT_SNAPSHOT_INTERVAL;
                int chunkInterval = serviceConfig.hasPath("options.chunkInterval")
                    ? serviceConfig.getInt("options.chunkInterval")
                    : SimulationParameters.DEFAULT_CHUNK_INTERVAL;
                double estimatedDeltaRatio = serviceConfig.hasPath("options.estimatedDeltaRatio")
                    ? serviceConfig.getDouble("options.estimatedDeltaRatio")
                    : SimulationParameters.DEFAULT_ESTIMATED_DELTA_RATIO;

                log.debug("Found SimulationEngine '{}' with environment shape {}, maxOrganisms={}, samplesPerChunk={}, simulationTicksPerChunk={}",
                    serviceName, Arrays.toString(shape), maxOrganisms,
                    accumulatedDeltaInterval * snapshotInterval * chunkInterval,
                    samplingInterval * accumulatedDeltaInterval * snapshotInterval * chunkInterval);
                return new SimulationParameters(
                    shape, totalCells, totalCells, maxOrganisms,
                    samplingInterval, accumulatedDeltaInterval,
                    snapshotInterval, chunkInterval, estimatedDeltaRatio
                );
            }
        }

        log.debug("No SimulationEngine service found in configuration");
        return null;
    }
    
    /**
     * Formats a byte count as a human-readable heap recommendation (e.g., "8g" or "2048m").
     *
     * @param bytes The recommended heap size in bytes
     * @return Formatted string suitable for -Xmx parameter
     */
    private String formatHeapRecommendation(long bytes) {
        long gb = bytes / (1024 * 1024 * 1024);
        if (gb >= 1) {
            // Round up to next GB
            return (gb + 1) + "g";
        } else {
            long mb = bytes / (1024 * 1024);
            // Round up to next 256 MB
            return ((mb / 256 + 1) * 256) + "m";
        }
    }
}