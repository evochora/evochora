package org.evochora.node.http.api.pipeline;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.javalin.http.Context;
import org.evochora.datapipeline.ServiceManager;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.datapipeline.api.services.ServiceStatus;
import org.evochora.node.http.api.pipeline.dto.PipelineStatusDto;
import org.evochora.node.http.api.pipeline.dto.ServiceStatusDto;
import org.evochora.node.spi.ServiceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PipelineController Unit Tests")
class PipelineControllerTest {

    @Mock
    private ServiceManager serviceManager;

    @Mock
    private ServiceRegistry serviceRegistry;

    @Mock
    private Context ctx;

    @Captor
    private ArgumentCaptor<PipelineStatusDto> pipelineStatusCaptor;

    @Captor
    private ArgumentCaptor<ServiceStatusDto> serviceStatusCaptor;

    private PipelineController controller;

    @BeforeEach
    void setUp() {
        lenient().when(serviceRegistry.get(ServiceManager.class)).thenReturn(serviceManager);
        final Config emptyConfig = ConfigFactory.empty();
        controller = new PipelineController(serviceRegistry, emptyConfig);
    }

    @Nested
    @DisplayName("Global Lifecycle Endpoints")
    class GlobalLifecycle {

        @Test
        @DisplayName("POST /start should call serviceManager.startAll")
        void postStart_shouldCallStartAll() {
            controller.handleLifecycleCommand(ctx, serviceManager::startAll);
            verify(serviceManager).startAll();
            verify(ctx).status(202);
        }

        @Test
        @DisplayName("GET /status should return overall pipeline status")
        void getStatus_shouldReturnPipelineStatus() {
            // Arrange
            final ServiceStatus consumerStatus = new ServiceStatus(IService.State.RUNNING, Collections.emptyMap(), Collections.emptyList(), Collections.emptyList());
            final Map<String, ServiceStatus> statuses = Map.of("consumer", consumerStatus);
            when(serviceManager.getAllServiceStatus()).thenReturn(statuses);

            // Act
            controller.getPipelineStatus(ctx);

            // Assert
            verify(ctx).status(200);
            verify(ctx).json(pipelineStatusCaptor.capture());

            final PipelineStatusDto result = pipelineStatusCaptor.getValue();
            assertThat(result.status()).isEqualTo("RUNNING");
            assertThat(result.services()).hasSize(1);
            assertThat(result.services().get(0).name()).isEqualTo("consumer");
            assertThat(result.services().get(0).state()).isEqualTo("RUNNING");
        }
    }

    @Nested
    @DisplayName("Individual Service Endpoints")
    class ServiceLifecycle {

        @BeforeEach
        void setupServiceContext() {
            when(ctx.pathParam("serviceName")).thenReturn("test-service");
        }

        @Test
        @DisplayName("GET /service/{name}/status should return status for a specific service")
        void getServiceStatus_shouldReturnCorrectStatus() {
            // Arrange
            final ServiceStatus testStatus = new ServiceStatus(IService.State.STOPPED, Map.of("metric", 1), Collections.emptyList(), Collections.emptyList());
            when(serviceManager.getServiceStatus("test-service")).thenReturn(testStatus);

            // Act
            controller.getServiceStatus(ctx);

            // Assert
            verify(ctx).status(200);
            verify(ctx).json(serviceStatusCaptor.capture());

            final ServiceStatusDto result = serviceStatusCaptor.getValue();
            assertThat(result.name()).isEqualTo("test-service");
            assertThat(result.state()).isEqualTo("STOPPED");
            // Corrected the type mismatch for the assertion.
            assertThat(result.metrics()).containsEntry("metric", Integer.valueOf(1));
        }

        @Test
        @DisplayName("GET /service/{name}/status should throw when service not found")
        void getServiceStatus_whenNotFound_shouldThrow() {
            // Arrange
            when(serviceManager.getServiceStatus(anyString()))
                .thenThrow(new IllegalArgumentException("Service not found: non-existent-service"));
            when(ctx.pathParam("serviceName")).thenReturn("non-existent-service");

            // Act & Assert
            // Verify that the method in the controller throws the expected exception,
            // which would then be caught by the Javalin exception handler.
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> {
                controller.getServiceStatus(ctx);
            });
        }

        @Test
        @DisplayName("POST /service/{name}/start should call serviceManager.startService with correct name")
        void postStartService_shouldCallStartService() {
            // Act
            controller.handleServiceLifecycleCommand(ctx, serviceManager::startService);

            // Assert
            verify(serviceManager).startService("test-service");
            verify(ctx).status(202);
        }

        @Test
        @DisplayName("POST /service/{name}/stop should call serviceManager.stopService with correct name")
        void postStopService_shouldCallStopService() {
            // Act
            controller.handleServiceLifecycleCommand(ctx, serviceManager::stopService);

            // Assert
            verify(serviceManager).stopService("test-service");
            verify(ctx).status(202);
        }
    }
}