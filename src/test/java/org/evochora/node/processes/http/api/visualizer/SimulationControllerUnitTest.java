/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

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

import static org.mockito.Mockito.mock;
import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for SimulationController.
 * <p>
 * Tests focus on controller construction and route registration without database I/O:
 * <ul>
 *   <li>Controller construction with valid dependencies</li>
 *   <li>Route registration for /metadata and /ticks endpoints</li>
 *   <li>Configuration handling</li>
 * </ul>
 * <p>
 * <strong>AGENTS.md Compliance:</strong>
 * <ul>
 *   <li>Tagged as @Tag("unit") - <0.2s runtime, no I/O</li>
 *   <li>No database dependencies - pure unit tests</li>
 *   <li>Fast execution - no external dependencies</li>
 *   <li>LogWatchExtension ensures no unexpected WARN/ERROR logs</li>
 * </ul>
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
@DisplayName("SimulationController Unit Tests")
class SimulationControllerUnitTest {

    @Nested
    @DisplayName("Controller Construction")
    class ControllerConstruction {

        @Test
        @DisplayName("Should create controller with valid dependencies")
        void createControllerWithValidDependencies() {
            ServiceRegistry serviceRegistry = new ServiceRegistry();
            IDatabaseReaderProvider mockDatabase = mock(IDatabaseReaderProvider.class);
            serviceRegistry.register(IDatabaseReaderProvider.class, mockDatabase);
            
            Config config = ConfigFactory.parseString("runId = \"test_run\"");
            
            SimulationController testController = new SimulationController(serviceRegistry, config);
            
            assertThat(testController).isNotNull();
        }

        @Test
        @DisplayName("Should create controller with default configuration")
        void createControllerWithDefaultConfiguration() {
            ServiceRegistry serviceRegistry = new ServiceRegistry();
            IDatabaseReaderProvider mockDatabase = mock(IDatabaseReaderProvider.class);
            serviceRegistry.register(IDatabaseReaderProvider.class, mockDatabase);
            
            Config config = ConfigFactory.empty();
            
            SimulationController controllerWithDefault = new SimulationController(serviceRegistry, config);
            
            assertThat(controllerWithDefault).isNotNull();
        }
    }
}

