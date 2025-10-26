package org.evochora.node.processes.http;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;
import io.javalin.Javalin;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.evochora.datapipeline.ServiceManager;
import org.evochora.datapipeline.api.resources.database.IDatabaseReaderProvider;
import org.evochora.node.spi.IController;
import org.evochora.node.processes.AbstractProcess;
import org.evochora.node.spi.ServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A manageable process that runs a Javalin HTTP server. It dynamically configures its routes
 * by parsing a 'routes' block in its configuration, loading controllers and static file handlers
 * as specified.
 *
 * <p>This process creates an internal ServiceRegistry for controllers to maintain backward
 * compatibility with the existing controller architecture.</p>
 */
public class HttpServerProcess extends AbstractProcess {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpServerProcess.class);

    private static final String ROUTES_CONFIG_KEY = "routes";
    // Keys are now quoted to be valid HOCON syntax.
    private static final String CONTROLLER_ACTION_KEY = "\"$controller\"";
    private static final String STATIC_ACTION_KEY = "\"$static\"";

    private final List<RouteDefinition> routeDefinitions = new ArrayList<>();
    private final ServiceRegistry controllerRegistry;
    private Javalin app;

    /**
     * Constructs a new HttpServerProcess.
     *
     * @param processName The name of this process instance from the configuration.
     * @param dependencies Dependencies injected by the Node (expects "serviceManager" from pipeline process).
     * @param options  The configuration for this process, including network settings and routes.
     */
    public HttpServerProcess(final String processName, final Map<String, Object> dependencies, final Config options) {
        super(processName, dependencies, options);

        // Extract ServiceManager dependency
        final ServiceManager serviceManager = getDependency("serviceManager", ServiceManager.class);

        // Create internal ServiceRegistry for controllers (backward compatibility)
        this.controllerRegistry = new ServiceRegistry();
        this.controllerRegistry.register(ServiceManager.class, serviceManager);
        
        // Register IDatabaseReaderProvider if configured
        if (options.hasPath("databaseProviderResourceName")) {
            final String dbProviderName = options.getString("databaseProviderResourceName");
            try {
                final IDatabaseReaderProvider dbProvider = serviceManager.getResource(dbProviderName, IDatabaseReaderProvider.class);
                this.controllerRegistry.register(IDatabaseReaderProvider.class, dbProvider);
                LOGGER.debug("Registered database provider '{}' for controllers", dbProviderName);
            } catch (final Exception e) {
                LOGGER.warn("Failed to register database provider '{}': {}", dbProviderName, e.getMessage());
            }
        }

        parseRoutes();
        LOGGER.debug("HttpServerProcess '{}' initialized with ServiceManager dependency.", processName);
    }

    @Override
    public void start() {
        if (app != null) {
            LOGGER.warn("HTTP server is already running.");
            return;
        }

        final String host = options.getString("network.host");
        final int port = options.getInt("network.port");

        app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.requestLogger.http((ctx, ms) -> {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Request: {} {} (completed in {} ms)", ctx.method(), ctx.path(), ms);
                }
            });

            // Configure custom thread pool with named threads for better monitoring
            final int minThreads = options.hasPath("network.threadPool.minThreads")
                ? options.getInt("network.threadPool.minThreads")
                : 8;
            final int maxThreads = options.hasPath("network.threadPool.maxThreads")
                ? options.getInt("network.threadPool.maxThreads")
                : 200;
            final int idleTimeout = options.hasPath("network.threadPool.idleTimeoutMs")
                ? options.getInt("network.threadPool.idleTimeoutMs")
                : 60000;

            final QueuedThreadPool threadPool = new QueuedThreadPool(
                maxThreads,
                minThreads,
                idleTimeout
            );
            threadPool.setName(processName);
            config.jetty.threadPool = threadPool;

            LOGGER.debug("Configured thread pool '{}' with {} min threads, {} max threads, {} ms idle timeout",
                threadPool.getName(), minThreads, maxThreads, idleTimeout);

            // Configure static file routes from the parsed definitions
            for (final RouteDefinition def : routeDefinitions) {
                if (def.type == RouteType.STATIC) {
                    try {
                        final String classpathDir = (String) def.configValue.unwrapped();
                        LOGGER.debug("Configuring static files from classpath '{}' at URL path '{}'", classpathDir, def.basePath);
                        config.staticFiles.add(staticFiles -> {
                            staticFiles.hostedPath = def.basePath;
                            staticFiles.directory = classpathDir;
                            staticFiles.location = io.javalin.http.staticfiles.Location.CLASSPATH;
                        });
                    } catch (final Exception e) {
                        LOGGER.error("Failed to configure static route at path '{}'", def.basePath, e);
                    }
                }
            }
        });

        // Register controller routes after the app instance is created
        registerControllers(app);

        app.start(host, port);
        LOGGER.info("HTTP server started on {}:{}", host, port);
    }

    @Override
    public void stop() {
        if (app != null) {
            app.stop();
            app = null;
            LOGGER.info("HTTP server stopped.");
        }
    }

    private void parseRoutes() {
        if (!options.hasPath(ROUTES_CONFIG_KEY)) {
            LOGGER.warn("No '{}' block found in http-server configuration. No routes will be served.", ROUTES_CONFIG_KEY);
            return;
        }
        final Config routesConfig = options.getConfig(ROUTES_CONFIG_KEY);
        parseConfigLevel(routesConfig.root(), "/");
    }

    private void parseConfigLevel(final ConfigObject configObject, final String currentPath) {
        for (final Map.Entry<String, ConfigValue> entry : configObject.entrySet()) {
            final String key = entry.getKey();
            final ConfigValue value = entry.getValue();

            // Check for the unquoted key for parsing, as Typesafe Config unnquotes it.
            if (key.equals(CONTROLLER_ACTION_KEY.replace("\"", ""))) {
                if (value.valueType() == ConfigValueType.OBJECT) {
                    routeDefinitions.add(new RouteDefinition(currentPath, RouteType.CONTROLLER, value));
                } else {
                    LOGGER.error("Invalid config for '$controller' at path '{}'. Expected an object.", currentPath);
                }
            } else if (key.equals(STATIC_ACTION_KEY.replace("\"", ""))) {
                if (value.valueType() == ConfigValueType.STRING) {
                    routeDefinitions.add(new RouteDefinition(currentPath, RouteType.STATIC, value));
                } else {
                    LOGGER.error("Invalid config for '$static' at path '{}'. Expected a string.", currentPath);
                }
            } else {
                if (value.valueType() == ConfigValueType.OBJECT) {
                    final String nextPath = (currentPath + key + "/").replaceAll("//", "/");
                    parseConfigLevel((ConfigObject) value, nextPath);
                }
            }
        }
    }

    private void registerControllers(final Javalin app) {
        for (final RouteDefinition def : routeDefinitions) {
            if (def.type == RouteType.CONTROLLER) {
                try {
                    registerController(def, app);
                } catch (final Exception e) {
                    LOGGER.error("Failed to register controller at path '{}'", def.basePath, e);
                }
            }
        }
    }

    private void registerController(final RouteDefinition def, final Javalin app) throws Exception {
        final Config controllerConfig = ((ConfigObject) def.configValue).toConfig();
        final String className = controllerConfig.getString("className");
        final Config controllerOptions = controllerConfig.hasPath("options")
            ? controllerConfig.getConfig("options")
            : ConfigFactory.empty();

        LOGGER.debug("Registering controller '{}' at base path '{}'", className, def.basePath);

        final Class<?> controllerClass = Class.forName(className);
        if (!IController.class.isAssignableFrom(controllerClass)) {
            throw new IllegalArgumentException("Class " + className + " does not implement IController.");
        }

        final Constructor<?> constructor = controllerClass.getConstructor(ServiceRegistry.class, Config.class);
        final IController controller = (IController) constructor.newInstance(controllerRegistry, controllerOptions);

        controller.registerRoutes(app, def.basePath);
    }

    private enum RouteType {
        CONTROLLER, STATIC
    }

    private static class RouteDefinition {
        private final String basePath;
        private final RouteType type;
        private final ConfigValue configValue;

        RouteDefinition(final String basePath, final RouteType type, final ConfigValue configValue) {
            this.basePath = Objects.requireNonNull(basePath);
            this.type = Objects.requireNonNull(type);
            this.configValue = Objects.requireNonNull(configValue);
        }
    }
}