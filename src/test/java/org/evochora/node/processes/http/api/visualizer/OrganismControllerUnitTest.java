package org.evochora.node.processes.http.api.visualizer;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.resources.database.IDatabaseReaderProvider;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.node.spi.ServiceRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link OrganismController}.
 * <p>
 * Focus on controller construction and configuration wiring without HTTP server or DB I/O.
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
@DisplayName("OrganismController Unit Tests")
class OrganismControllerUnitTest {

    @Nested
    @DisplayName("Controller Construction")
    class ControllerConstruction {

        @Test
        @DisplayName("Should create controller with valid dependencies")
        void createControllerWithValidDependencies() {
            ServiceRegistry serviceRegistry = new ServiceRegistry();
            IDatabaseReaderProvider mockDatabase = mock(IDatabaseReaderProvider.class);
            serviceRegistry.register(IDatabaseReaderProvider.class, mockDatabase);

            Config config = ConfigFactory.parseString("cache{}");

            OrganismController controller = new OrganismController(serviceRegistry, config);

            assertThat(controller).isNotNull();
        }

        @Test
        @DisplayName("Should create controller with default configuration")
        void createControllerWithDefaultConfiguration() {
            ServiceRegistry serviceRegistry = new ServiceRegistry();
            IDatabaseReaderProvider mockDatabase = mock(IDatabaseReaderProvider.class);
            serviceRegistry.register(IDatabaseReaderProvider.class, mockDatabase);

            Config config = ConfigFactory.empty();

            OrganismController controller = new OrganismController(serviceRegistry, config);

            assertThat(controller).isNotNull();
        }
    }
}



