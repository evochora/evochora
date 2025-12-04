package org.evochora.node.processes.http.api.pipeline;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.javalin.http.Context;
import org.evochora.datapipeline.ServiceManager;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.datapipeline.api.services.ServiceStatus;
import org.evochora.node.processes.http.api.pipeline.dto.PipelineStatusDto;
import org.evochora.node.processes.http.api.pipeline.dto.ServiceStatusDto;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        // Configure the mocks BEFORE creating the controller
        when(serviceRegistry.get(ServiceManager.class)).thenReturn(serviceManager);
        
        // Configure Context mock for fluent interface
        lenient().when(ctx.status(any())).thenReturn(ctx);
        lenient().when(ctx.json(any())).thenReturn(ctx);
        
        final Config emptyConfig = ConfigFactory.empty();
        controller = new PipelineController(serviceRegistry, emptyConfig);
    }

    @Nested
    @DisplayName("Public API Methods")
    class PublicApiMethods {

        @Test
        @DisplayName("getPipelineStatus should return overall pipeline status")
        void getPipelineStatus_shouldReturnPipelineStatus() {
            // Arrange
            final ServiceStatus consumerStatus = new ServiceStatus(IService.State.RUNNING, true, Collections.emptyMap(), Collections.emptyList(), Collections.emptyList());
            final Map<String, ServiceStatus> statuses = Map.of("consumer", consumerStatus);
            when(serviceManager.getAllServiceStatus()).thenReturn(statuses);

            // Act
            controller.getPipelineStatus(ctx);

            // Assert
            verify(ctx).status(any());
            verify(ctx).json(pipelineStatusCaptor.capture());

            final PipelineStatusDto result = pipelineStatusCaptor.getValue();
            assertThat(result.status()).isEqualTo("RUNNING");
            assertThat(result.services()).hasSize(1);
            assertThat(result.services().get(0).name()).isEqualTo("consumer");
            assertThat(result.services().get(0).state()).isEqualTo("RUNNING");
        }

        @Test
        @DisplayName("getServiceStatus should return status for a specific service")
        void getServiceStatus_shouldReturnCorrectStatus() {
            // Arrange
            final ServiceStatus testStatus = new ServiceStatus(IService.State.STOPPED, true, Map.of("metric", 1), Collections.emptyList(), Collections.emptyList());
            when(serviceManager.getServiceStatus("test-service")).thenReturn(testStatus);
            when(ctx.pathParam("serviceName")).thenReturn("test-service");

            // Act
            controller.getServiceStatus(ctx);

            // Assert
            verify(ctx).status(any());
            verify(ctx).json(serviceStatusCaptor.capture());

            final ServiceStatusDto result = serviceStatusCaptor.getValue();
            assertThat(result.name()).isEqualTo("test-service");
            assertThat(result.state()).isEqualTo("STOPPED");
            assertThat(result.metrics()).containsEntry("metric", Integer.valueOf(1));
        }

        @Test
        @DisplayName("getServiceStatus should throw when service not found")
        void getServiceStatus_whenNotFound_shouldThrow() {
            // Arrange
            when(serviceManager.getServiceStatus(anyString()))
                .thenThrow(new IllegalArgumentException("Service not found: non-existent-service"));
            when(ctx.pathParam("serviceName")).thenReturn("non-existent-service");

            // Act & Assert
            assertThrows(IllegalArgumentException.class, () -> {
                controller.getServiceStatus(ctx);
            });
        }
    }

}