package org.evochora.node.http.api.pipeline;

import com.typesafe.config.Config;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import org.evochora.datapipeline.ServiceManager;
import org.evochora.datapipeline.api.services.ServiceStatus;
import org.evochora.node.http.AbstractController;
import org.evochora.node.http.api.pipeline.dto.PipelineStatusDto;
import org.evochora.node.http.api.pipeline.dto.ServiceStatusDto;
import org.evochora.node.spi.ServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Controller that exposes a REST API for controlling and monitoring the data pipeline.
 * It provides endpoints to manage the lifecycle of the entire pipeline and its individual services.
 */
public class PipelineController extends AbstractController {

    private static final Logger LOGGER = LoggerFactory.getLogger(PipelineController.class);
    private final ServiceManager serviceManager;
    private final String nodeId;

    /**
     * Constructs a new PipelineController.
     *
     * @param registry The central service registry.
     * @param options  The configuration for this controller.
     */
    public PipelineController(final ServiceRegistry registry, final Config options) {
        super(registry, options);
        this.serviceManager = registry.get(ServiceManager.class);
        this.nodeId = determineNodeId();
    }

    @Override
    public void registerRoutes(final Javalin app, final String basePath) {
        final String fullPath = (basePath).replaceAll("//", "/");

        // Global control
        app.get(fullPath + "status", this::getPipelineStatus);
        app.post(fullPath + "start", ctx -> handleLifecycleCommand(ctx, serviceManager::startAll));
        app.post(fullPath + "stop", ctx -> handleLifecycleCommand(ctx, serviceManager::stopAll));
        app.post(fullPath + "restart", ctx -> handleLifecycleCommand(ctx, serviceManager::restartAll));
        app.post(fullPath + "pause", ctx -> handleLifecycleCommand(ctx, serviceManager::pauseAll));
        app.post(fullPath + "resume", ctx -> handleLifecycleCommand(ctx, serviceManager::resumeAll));

        // Individual service control
        final String servicePath = fullPath + "service/{serviceName}";
        app.get(servicePath + "/status", this::getServiceStatus);
        app.post(servicePath + "/start", ctx -> handleServiceLifecycleCommand(ctx, serviceManager::startService));
        app.post(servicePath + "/stop", ctx -> handleServiceLifecycleCommand(ctx, serviceManager::stopService));
        app.post(servicePath + "/restart", ctx -> handleServiceLifecycleCommand(ctx, serviceManager::restartService));
        app.post(servicePath + "/pause", ctx -> handleServiceLifecycleCommand(ctx, serviceManager::pauseService));
        app.post(servicePath + "/resume", ctx -> handleServiceLifecycleCommand(ctx, serviceManager::resumeService));

        // Exception handling
        app.exception(IllegalArgumentException.class, (e, ctx) -> {
            // This is typically thrown by ServiceManager when a service is not found.
            LOGGER.warn("Not found handler triggered for request {}: {}", ctx.path(), e.getMessage());
            ctx.status(HttpStatus.NOT_FOUND).json(createErrorBody(HttpStatus.NOT_FOUND, e.getMessage()));
        });
        app.exception(IllegalStateException.class, (e, ctx) -> {
            LOGGER.warn("Invalid state transition for request {}: {}", ctx.path(), e.getMessage());
            ctx.status(HttpStatus.CONFLICT).json(createErrorBody(HttpStatus.CONFLICT, e.getMessage()));
        });
        app.exception(Exception.class, (e, ctx) -> {
            LOGGER.error("Unhandled exception for request {}", ctx.path(), e);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(createErrorBody(HttpStatus.INTERNAL_SERVER_ERROR, "An internal server error occurred."));
        });
    }

    void getPipelineStatus(final Context ctx) {
        final List<ServiceStatusDto> serviceStatuses = serviceManager.getAllServiceStatus().entrySet().stream()
            .map(entry -> ServiceStatusDto.from(entry.getKey(), entry.getValue()))
            .collect(Collectors.toList());
        final String overallStatus = determineOverallStatus(serviceStatuses);
        ctx.status(HttpStatus.OK).json(new PipelineStatusDto(nodeId, overallStatus, serviceStatuses));
    }

    void getServiceStatus(final Context ctx) {
        final String serviceName = ctx.pathParam("serviceName");
        final ServiceStatus status = serviceManager.getServiceStatus(serviceName); // Throws NoSuchElementException if not found
        ctx.status(HttpStatus.OK).json(ServiceStatusDto.from(serviceName, status));
    }

    void handleLifecycleCommand(final Context ctx, final Runnable command) {
        command.run();
        ctx.status(HttpStatus.ACCEPTED).json(Map.of("message", "Request accepted."));
    }

    void handleServiceLifecycleCommand(final Context ctx, final ServiceCommand command) {
        final String serviceName = ctx.pathParam("serviceName");
        command.execute(serviceName);
        ctx.status(HttpStatus.ACCEPTED).json(Map.of("message", "Request for service '" + serviceName + "' accepted."));
    }

    private String determineOverallStatus(final List<ServiceStatusDto> services) {
        if (services.isEmpty()) {
            return "IDLE";
        }
        boolean hasRunning = false;
        boolean hasStoppedOrError = false;

        for (final ServiceStatusDto service : services) {
            switch (service.state()) {
                case "RUNNING":
                    hasRunning = true;
                    break;
                case "STOPPED":
                case "ERROR":
                    hasStoppedOrError = true;
                    break;
                // PAUSED state is also considered not fully running, leading to DEGRADED
                case "PAUSED":
                    hasStoppedOrError = true;
                    break;
            }
        }

        if (hasRunning && hasStoppedOrError) {
            return "DEGRADED";
        }
        if (hasRunning) {
            return "RUNNING";
        }
        return "STOPPED";
    }

    private String determineNodeId() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (final UnknownHostException e) {
            LOGGER.warn("Could not determine hostname for node ID, falling back to 'unknown'.", e);
            return "unknown";
        }
    }

    private Map<String, Object> createErrorBody(final HttpStatus status, final String message) {
        return Map.of(
            "timestamp", Instant.now().toString(),
            "status", status.getCode(),
            "error", status.getMessage(),
            "message", message
        );
    }

    @FunctionalInterface
    interface ServiceCommand {
        void execute(String serviceName);
    }
}