package org.evochora.node.processes.http.api.pipeline;

import com.typesafe.config.Config;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;
import org.evochora.datapipeline.ServiceManager;
import org.evochora.datapipeline.api.services.ServiceStatus;
import org.evochora.node.processes.http.AbstractController;
import org.evochora.node.processes.http.api.pipeline.dto.ErrorResponseDto;
import org.evochora.node.processes.http.api.pipeline.dto.MessageResponseDto;
import org.evochora.node.processes.http.api.pipeline.dto.PipelineStatusDto;
import org.evochora.node.processes.http.api.pipeline.dto.ResourceStatusDto;
import org.evochora.node.processes.http.api.pipeline.dto.ServiceStatusDto;
import org.evochora.node.spi.ServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
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
        app.post(fullPath + "start", this::handleStart);
        app.post(fullPath + "stop", this::handleStop);
        app.post(fullPath + "restart", this::handleRestart);
        app.post(fullPath + "pause", this::handlePause);
        app.post(fullPath + "resume", this::handleResume);

        // Individual service control
        final String servicePath = fullPath + "service/{serviceName}";
        app.get(servicePath + "/status", this::getServiceStatus);
        app.post(servicePath + "/start", this::handleServiceStart);
        app.post(servicePath + "/stop", this::handleServiceStop);
        app.post(servicePath + "/restart", this::handleServiceRestart);
        app.post(servicePath + "/pause", this::handleServicePause);
        app.post(servicePath + "/resume", this::handleServiceResume);

        // Exception handling
        app.exception(IllegalArgumentException.class, (e, ctx) -> {
            // This is typically thrown by ServiceManager when a service is not found.
            LOGGER.warn("Not found handler triggered for request {}: {}", ctx.path(), e.getMessage());
            ctx.status(HttpStatus.NOT_FOUND).json(ErrorResponseDto.of(
                HttpStatus.NOT_FOUND.getCode(),
                HttpStatus.NOT_FOUND.getMessage(),
                e.getMessage()
            ));
        });
        app.exception(IllegalStateException.class, (e, ctx) -> {
            LOGGER.warn("Invalid state transition for request {}: {}", ctx.path(), e.getMessage());
            ctx.status(HttpStatus.CONFLICT).json(ErrorResponseDto.of(
                HttpStatus.CONFLICT.getCode(),
                HttpStatus.CONFLICT.getMessage(),
                e.getMessage()
            ));
        });
        app.exception(Exception.class, (e, ctx) -> {
            LOGGER.error("Unhandled exception for request {}", ctx.path(), e);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(ErrorResponseDto.of(
                HttpStatus.INTERNAL_SERVER_ERROR.getCode(),
                HttpStatus.INTERNAL_SERVER_ERROR.getMessage(),
                "An internal server error occurred."
            ));
        });
    }

    @OpenApi(
        path = "status",
        methods = {HttpMethod.GET},
        summary = "Get pipeline status",
        description = "Returns the overall status of the data pipeline including all services and resources",
        tags = {"pipeline /"},
        responses = {
            @OpenApiResponse(status = "200", content = @OpenApiContent(from = PipelineStatusDto.class))
        }
    )
    void getPipelineStatus(final Context ctx) {
        final List<ServiceStatusDto> serviceStatuses = serviceManager.getAllServiceStatus().entrySet().stream()
            .map(entry -> ServiceStatusDto.from(entry.getKey(), entry.getValue()))
            .collect(Collectors.toList());

        final List<ResourceStatusDto> resourceStatuses = serviceManager.getAllResourceStatus().entrySet().stream()
            .map(entry -> ResourceStatusDto.from(entry.getKey(), entry.getValue()))
            .collect(Collectors.toList());

        final String overallStatus = determineOverallStatus(serviceStatuses);
        ctx.status(HttpStatus.OK).json(new PipelineStatusDto(nodeId, overallStatus, serviceStatuses, resourceStatuses));
    }

    @OpenApi(
        path = "service/{serviceName}/status",
        methods = {HttpMethod.GET},
        summary = "Get service status",
        description = "Returns the status of a specific service",
        tags = {"pipeline / services"},
        pathParams = {
            @OpenApiParam(name = "serviceName", description = "Name of the service", required = true)
        },
        responses = {
            @OpenApiResponse(status = "200", content = @OpenApiContent(from = ServiceStatusDto.class)),
            @OpenApiResponse(
                status = "404",
                description = "Service not found",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            ),
            @OpenApiResponse(
                status = "500",
                description = "Internal server error",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            )
        }
    )
    void getServiceStatus(final Context ctx) {
        final String serviceName = ctx.pathParam("serviceName");
        final ServiceStatus status = serviceManager.getServiceStatus(serviceName); // Throws NoSuchElementException if not found
        ctx.status(HttpStatus.OK).json(ServiceStatusDto.from(serviceName, status));
    }

    void handleLifecycleCommand(final Context ctx, final Runnable command) {
        command.run();
        ctx.status(HttpStatus.ACCEPTED).json(new MessageResponseDto("Request accepted."));
    }

    void handleServiceLifecycleCommand(final Context ctx, final ServiceCommand command) {
        final String serviceName = ctx.pathParam("serviceName");
        command.execute(serviceName);
        ctx.status(HttpStatus.ACCEPTED).json(new MessageResponseDto("Request for service '" + serviceName + "' accepted."));
    }

    // Wrapper methods for OpenAPI annotations (replacing lambdas)

    @OpenApi(
        path = "start",
        methods = {HttpMethod.POST},
        summary = "Start all services",
        description = "Starts all services in the pipeline",
        tags = {"pipeline /"},
        responses = {
            @OpenApiResponse(
                status = "202",
                description = "Request accepted (best-effort: individual service failures are logged but do not fail the request)",
                content = @OpenApiContent(from = MessageResponseDto.class)
            ),
            @OpenApiResponse(
                status = "404",
                description = "Service not found",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            ),
            @OpenApiResponse(
                status = "409",
                description = "Invalid state transition (e.g., service already running or paused)",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            ),
            @OpenApiResponse(
                status = "500",
                description = "Internal server error",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            )
        }
    )
    void handleStart(final Context ctx) {
        handleLifecycleCommand(ctx, serviceManager::startAll);
    }

    @OpenApi(
        path = "stop",
        methods = {HttpMethod.POST},
        summary = "Stop all services",
        description = "Stops all services in the pipeline",
        tags = {"pipeline /"},
        responses = {
            @OpenApiResponse(
                status = "202",
                description = "Request accepted (best-effort: individual service failures are logged but do not fail the request)",
                content = @OpenApiContent(from = MessageResponseDto.class)
            ),
            @OpenApiResponse(
                status = "404",
                description = "Service not found",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            ),
            @OpenApiResponse(
                status = "409",
                description = "Invalid state transition (e.g., service already running or paused)",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            ),
            @OpenApiResponse(
                status = "500",
                description = "Internal server error",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            )
        }
    )
    void handleStop(final Context ctx) {
        handleLifecycleCommand(ctx, serviceManager::stopAll);
    }

    @OpenApi(
        path = "restart",
        methods = {HttpMethod.POST},
        summary = "Restart all services",
        description = "Restarts all services in the pipeline",
        tags = {"pipeline /"},
        responses = {
            @OpenApiResponse(
                status = "202",
                description = "Request accepted (best-effort: individual service failures are logged but do not fail the request)",
                content = @OpenApiContent(from = MessageResponseDto.class)
            ),
            @OpenApiResponse(
                status = "404",
                description = "Service not found",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            ),
            @OpenApiResponse(
                status = "409",
                description = "Invalid state transition (e.g., service already running or paused)",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            ),
            @OpenApiResponse(
                status = "500",
                description = "Internal server error",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            )
        }
    )
    void handleRestart(final Context ctx) {
        handleLifecycleCommand(ctx, serviceManager::restartAll);
    }

    @OpenApi(
        path = "pause",
        methods = {HttpMethod.POST},
        summary = "Pause all services",
        description = "Pauses all services in the pipeline",
        tags = {"pipeline /"},
        responses = {
            @OpenApiResponse(
                status = "202",
                description = "Request accepted (best-effort: individual service failures are logged but do not fail the request)",
                content = @OpenApiContent(from = MessageResponseDto.class)
            ),
            @OpenApiResponse(
                status = "404",
                description = "Service not found",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            ),
            @OpenApiResponse(
                status = "409",
                description = "Invalid state transition (e.g., service already running or paused)",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            ),
            @OpenApiResponse(
                status = "500",
                description = "Internal server error",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            )
        }
    )
    void handlePause(final Context ctx) {
        handleLifecycleCommand(ctx, serviceManager::pauseAll);
    }

    @OpenApi(
        path = "resume",
        methods = {HttpMethod.POST},
        summary = "Resume all services",
        description = "Resumes all services in the pipeline",
        tags = {"pipeline /"},
        responses = {
            @OpenApiResponse(
                status = "202",
                description = "Request accepted (best-effort: individual service failures are logged but do not fail the request)",
                content = @OpenApiContent(from = MessageResponseDto.class)
            ),
            @OpenApiResponse(
                status = "404",
                description = "Service not found",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            ),
            @OpenApiResponse(
                status = "409",
                description = "Invalid state transition (e.g., service already running or paused)",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            ),
            @OpenApiResponse(
                status = "500",
                description = "Internal server error",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            )
        }
    )
    void handleResume(final Context ctx) {
        handleLifecycleCommand(ctx, serviceManager::resumeAll);
    }

    @OpenApi(
        path = "service/{serviceName}/start",
        methods = {HttpMethod.POST},
        summary = "Start a specific service",
        description = "Starts a specific service by name",
        tags = {"pipeline / services"},
        pathParams = {
            @OpenApiParam(name = "serviceName", description = "Name of the service", required = true)
        },
        responses = {
            @OpenApiResponse(
                status = "202",
                description = "Request accepted",
                content = @OpenApiContent(from = MessageResponseDto.class)
            ),
            @OpenApiResponse(
                status = "404",
                description = "Service not found",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            ),
            @OpenApiResponse(
                status = "409",
                description = "Invalid state transition (e.g., service already running or paused)",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            ),
            @OpenApiResponse(
                status = "500",
                description = "Internal server error",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            )
        }
    )
    void handleServiceStart(final Context ctx) {
        handleServiceLifecycleCommand(ctx, serviceManager::startService);
    }

    @OpenApi(
        path = "service/{serviceName}/stop",
        methods = {HttpMethod.POST},
        summary = "Stop a specific service",
        description = "Stops a specific service by name",
        tags = {"pipeline / services"},
        pathParams = {
            @OpenApiParam(name = "serviceName", description = "Name of the service", required = true)
        },
        responses = {
            @OpenApiResponse(
                status = "202",
                description = "Request accepted",
                content = @OpenApiContent(from = MessageResponseDto.class)
            ),
            @OpenApiResponse(
                status = "404",
                description = "Service not found",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            ),
            @OpenApiResponse(
                status = "409",
                description = "Invalid state transition (e.g., service already running or paused)",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            ),
            @OpenApiResponse(
                status = "500",
                description = "Internal server error",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            )
        }
    )
    void handleServiceStop(final Context ctx) {
        handleServiceLifecycleCommand(ctx, serviceManager::stopService);
    }

    @OpenApi(
        path = "service/{serviceName}/restart",
        methods = {HttpMethod.POST},
        summary = "Restart a specific service",
        description = "Restarts a specific service by name",
        tags = {"pipeline / services"},
        pathParams = {
            @OpenApiParam(name = "serviceName", description = "Name of the service", required = true)
        },
        responses = {
            @OpenApiResponse(
                status = "202",
                description = "Request accepted",
                content = @OpenApiContent(from = MessageResponseDto.class)
            ),
            @OpenApiResponse(
                status = "404",
                description = "Service not found",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            ),
            @OpenApiResponse(
                status = "409",
                description = "Invalid state transition (e.g., service already running or paused)",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            ),
            @OpenApiResponse(
                status = "500",
                description = "Internal server error",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            )
        }
    )
    void handleServiceRestart(final Context ctx) {
        handleServiceLifecycleCommand(ctx, serviceManager::restartService);
    }

    @OpenApi(
        path = "service/{serviceName}/pause",
        methods = {HttpMethod.POST},
        summary = "Pause a specific service",
        description = "Pauses a specific service by name",
        tags = {"pipeline / services"},
        pathParams = {
            @OpenApiParam(name = "serviceName", description = "Name of the service", required = true)
        },
        responses = {
            @OpenApiResponse(
                status = "202",
                description = "Request accepted",
                content = @OpenApiContent(from = MessageResponseDto.class)
            ),
            @OpenApiResponse(
                status = "404",
                description = "Service not found",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            ),
            @OpenApiResponse(
                status = "409",
                description = "Invalid state transition (e.g., service already running or paused)",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            ),
            @OpenApiResponse(
                status = "500",
                description = "Internal server error",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            )
        }
    )
    void handleServicePause(final Context ctx) {
        handleServiceLifecycleCommand(ctx, serviceManager::pauseService);
    }

    @OpenApi(
        path = "service/{serviceName}/resume",
        methods = {HttpMethod.POST},
        summary = "Resume a specific service",
        description = "Resumes a specific service by name",
        tags = {"pipeline / services"},
        pathParams = {
            @OpenApiParam(name = "serviceName", description = "Name of the service", required = true)
        },
        responses = {
            @OpenApiResponse(
                status = "202",
                description = "Request accepted",
                content = @OpenApiContent(from = MessageResponseDto.class)
            ),
            @OpenApiResponse(
                status = "404",
                description = "Service not found",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            ),
            @OpenApiResponse(
                status = "409",
                description = "Invalid state transition (e.g., service already running or paused)",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            ),
            @OpenApiResponse(
                status = "500",
                description = "Internal server error",
                content = @OpenApiContent(from = ErrorResponseDto.class)
            )
        }
    )
    void handleServiceResume(final Context ctx) {
        handleServiceLifecycleCommand(ctx, serviceManager::resumeService);
    }

    /**
     * Determines the overall node status based on service health and state.
     * <p>
     * This method uses {@code isHealthy()} as the single source of truth for service health,
     * allowing services to define their own health semantics across all states.
     * <p>
     * Status logic:
     * <ul>
     *   <li><b>IDLE:</b> No services configured</li>
     *   <li><b>DEGRADED:</b> Any service reports unhealthy (regardless of state)</li>
     *   <li><b>RUNNING:</b> At least one service is RUNNING and all services are healthy</li>
     *   <li><b>STOPPED:</b> No services RUNNING and all services are healthy</li>
     * </ul>
     * <p>
     * This approach correctly handles:
     * <ul>
     *   <li>One-shot services (STOPPED + healthy → not degraded)</li>
     *   <li>Running services with internal errors (RUNNING + unhealthy → degraded)</li>
     *   <li>Intentionally paused services (PAUSED + healthy → not degraded)</li>
     * </ul>
     *
     * @param services List of service status DTOs
     * @return Overall node status: "IDLE", "RUNNING", "DEGRADED", or "STOPPED"
     */
    private String determineOverallStatus(final List<ServiceStatusDto> services) {
        if (services.isEmpty()) {
            return "IDLE";
        }

        boolean hasRunning = false;
        boolean hasUnhealthy = false;

        for (final ServiceStatusDto service : services) {
            if ("RUNNING".equals(service.state())) {
                hasRunning = true;
            }
            if (!service.healthy()) {
                hasUnhealthy = true;
            }
        }

        // Any unhealthy service causes DEGRADED status (regardless of state)
        if (hasUnhealthy) {
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


    @FunctionalInterface
    interface ServiceCommand {
        void execute(String serviceName);
    }
}