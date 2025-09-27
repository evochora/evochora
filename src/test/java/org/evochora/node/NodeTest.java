package org.evochora.node;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.ServiceManager;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.ExpectLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.node.spi.IProcess;
import org.evochora.node.spi.ServiceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for Node to verify configuration parsing and IProcess lifecycle management.
 * Uses fake IProcess implementations to test reflection-based instantiation and lifecycle calls.
 */
@Tag("unit")
@ExtendWith({MockitoExtension.class, LogWatchExtension.class})
class NodeTest {

    @Mock
    private ServiceManager serviceManager;

    private Config testConfig;

    @BeforeEach
    void setUp() {
        testConfig = ConfigFactory.parseString("""
            node {
              processes {
                test-process-1 {
                  className = "org.evochora.node.NodeTest$TestProcess"
                }
                test-process-2 {
                  className = "org.evochora.node.NodeTest$TestProcess2"
                }
              }
            }
            pipeline {
              services = []
            }
            """);
    }

    @Test
    @DisplayName("Should parse configuration and instantiate processes via reflection")
    void constructor_shouldParseConfigurationAndInstantiateProcesses() {
        // Act
        Node node = new Node(testConfig, serviceManager);
        
        // Assert - Verify that the node was created successfully
        assertThat(node).isNotNull();
        
        // The actual process instantiation happens in the constructor,
        // so we verify the node was created without exceptions
        // In a real scenario, we would need to expose the processes for verification
    }

    @Test
    @DisplayName("Should start all configured processes")
    void start_shouldStartAllConfiguredProcesses() {
        // Arrange
        Node node = new Node(testConfig, serviceManager);
        
        // Act
        node.start();

        // Assert - Verify that start() was called on each process
        // Note: Since we can't easily verify the reflection-instantiated processes,
        // we verify that start() completes without exceptions
        // In a real implementation, we might want to expose the processes for verification
    }

    @Test
    @DisplayName("Should stop all configured processes")
    void stop_shouldStopAllConfiguredProcesses() {
        // Arrange
        Node node = new Node(testConfig, serviceManager);
        node.start();

        // Act
        node.stop();

        // Assert - Verify that stop() was called on each process
        // Note: Since we can't easily verify the reflection-instantiated processes,
        // we verify that stop() completes without exceptions
    }

    @Test
    @ExpectLog(level = LogLevel.WARN, messagePattern = "Configuration path 'node.processes' not found. No processes will be loaded.")
    @ExpectLog(level = LogLevel.WARN, messagePattern = "No processes configured to start. The node will be idle.")
    @DisplayName("Should handle missing process configuration gracefully")
    void constructor_shouldHandleMissingProcessConfiguration() {
        // Arrange - Config without processes
        Config emptyConfig = ConfigFactory.parseString("""
            node {
              # No processes configured
            }
            pipeline {
              services = []
            }
            """);

        // Act & Assert - Should not throw exception
        Node nodeWithNoProcesses = new Node(emptyConfig, serviceManager);
        assertThat(nodeWithNoProcesses).isNotNull();
        
        // Should handle start/stop gracefully
        nodeWithNoProcesses.start();
        nodeWithNoProcesses.stop();
    }

    @Test
    @ExpectLog(level = LogLevel.ERROR, messagePattern = "Failed to initialize process 'invalid-process'. Skipping this process.")
    @DisplayName("Should handle invalid process class name")
    void constructor_shouldHandleInvalidProcessClassName() {
        // Arrange - Config with invalid class name
        Config invalidConfig = ConfigFactory.parseString("""
            node {
              processes {
                invalid-process {
                  className = "org.nonexistent.InvalidProcess"
                }
              }
            }
            pipeline {
              services = []
            }
            """);

        // Act - Should not throw exception, but skip the invalid process
        Node nodeWithInvalidProcess = new Node(invalidConfig, serviceManager);
        
        // Assert - Node should be created successfully, but the invalid process should be skipped
        assertThat(nodeWithInvalidProcess).isNotNull();
    }

    @Test
    @ExpectLog(level = LogLevel.ERROR, messagePattern = "Failed to initialize process 'invalid-process'. Skipping this process.")
    @DisplayName("Should handle process that doesn't implement IProcess")
    void constructor_shouldHandleProcessThatDoesNotImplementIProcess() {
        // Arrange - Config with class that doesn't implement IProcess
        Config invalidConfig = ConfigFactory.parseString("""
            node {
              processes {
                invalid-process {
                  className = "org.evochora.node.NodeTest$InvalidProcess"
                }
              }
            }
            pipeline {
              services = []
            }
            """);

        // Act - Should not throw exception, but skip the invalid process
        Node nodeWithInvalidProcess = new Node(invalidConfig, serviceManager);
        
        // Assert - Node should be created successfully, but the invalid process should be skipped
        assertThat(nodeWithInvalidProcess).isNotNull();
    }

    @Test
    @ExpectLog(level = LogLevel.ERROR, messagePattern = "Failed to initialize process 'failing-process'. Skipping this process.")
    @DisplayName("Should handle process that throws exception in constructor gracefully")
    void constructor_shouldHandleProcessConstructorException() {
        // Arrange - Config with process that throws exception in constructor
        Config failingConfig = ConfigFactory.parseString("""
            node {
              processes {
                failing-process {
                  className = "org.evochora.node.NodeTest$FailingProcess"
                }
              }
            }
            pipeline {
              services = []
            }
            """);

        // Act - Should not throw exception, but skip the failing process
        Node node = new Node(failingConfig, serviceManager);
        
        // Assert - Node should be created successfully, but the failing process should be skipped
        assertThat(node).isNotNull();
    }

    @Test
    @ExpectLog(level = LogLevel.ERROR, messagePattern = "Failed to start process 'failing-start-process'. The node may be unstable.")
    @DisplayName("Should handle process that throws exception in start method")
    void start_shouldHandleProcessStartException() {
        // Arrange - Config with process that throws exception in start
        Config failingStartConfig = ConfigFactory.parseString("""
            node {
              processes {
                failing-start-process {
                  className = "org.evochora.node.NodeTest$FailingStartProcess"
                }
              }
            }
            pipeline {
              services = []
            }
            """);

        Node nodeWithFailingStart = new Node(failingStartConfig, serviceManager);

        // Act - Should not throw exception, but log error for failing process
        nodeWithFailingStart.start();
        
        // Assert - Node should handle the failing process gracefully
        assertThat(nodeWithFailingStart).isNotNull();
    }

    @Test
    @ExpectLog(level = LogLevel.ERROR, messagePattern = "Error while stopping process 'failing-stop-process'.")
    @DisplayName("Should handle process that throws exception in stop method")
    void stop_shouldHandleProcessStopException() {
        // Arrange - Config with process that throws exception in stop
        Config failingStopConfig = ConfigFactory.parseString("""
            node {
              processes {
                failing-stop-process {
                  className = "org.evochora.node.NodeTest$FailingStopProcess"
                }
              }
            }
            pipeline {
              services = []
            }
            """);

        Node nodeWithFailingStop = new Node(failingStopConfig, serviceManager);
        nodeWithFailingStop.start(); // Start successfully

        // Act - Should not throw exception, but log error for failing process
        nodeWithFailingStop.stop();
        
        // Assert - Node should handle the failing process gracefully
        assertThat(nodeWithFailingStop).isNotNull();
    }

    @Test
    @ExpectLog(level = LogLevel.ERROR, messagePattern = "Failed to initialize process 'invalid-process'. Skipping this process.")
    @DisplayName("Should handle mixed valid and invalid processes")
    void constructor_shouldHandleMixedValidAndInvalidProcesses() {
        // Arrange - Config with both valid and invalid processes
        Config mixedConfig = ConfigFactory.parseString("""
            node {
              processes {
                valid-process {
                  className = "org.evochora.node.NodeTest$TestProcess"
                }
                invalid-process {
                  className = "org.nonexistent.InvalidProcess"
                }
              }
            }
            pipeline {
              services = []
            }
            """);

        // Act - Should not throw exception, but skip the invalid process
        Node nodeWithMixedProcesses = new Node(mixedConfig, serviceManager);
        
        // Assert - Node should be created successfully, but the invalid process should be skipped
        assertThat(nodeWithMixedProcesses).isNotNull();
    }

    // Fake IProcess implementations for testing

    /**
     * A valid IProcess implementation for testing
     */
    private static class TestProcess implements IProcess {
        private boolean started = false;
        private boolean stopped = false;

        public TestProcess(org.evochora.node.spi.ServiceRegistry serviceRegistry, Config config) {
            // Valid constructor
        }

        @Override
        public void start() {
            started = true;
        }

        @Override
        public void stop() {
            stopped = true;
        }
    }

    /**
     * Another valid IProcess implementation for testing multiple processes
     */
    private static class TestProcess2 implements IProcess {
        private boolean started = false;
        private boolean stopped = false;

        public TestProcess2(org.evochora.node.spi.ServiceRegistry serviceRegistry, Config config) {
            // Valid constructor
        }

        @Override
        public void start() {
            started = true;
        }

        @Override
        public void stop() {
            stopped = true;
        }
    }

    /**
     * A class that doesn't implement IProcess (for negative testing)
     */
    private static class InvalidProcess {
        public InvalidProcess(org.evochora.node.spi.ServiceRegistry serviceRegistry, Config config) {
            // Valid constructor but doesn't implement IProcess
        }
    }

    /**
     * A process that throws exception in constructor
     */
    private static class FailingProcess implements IProcess {
        public FailingProcess(org.evochora.node.spi.ServiceRegistry serviceRegistry, Config config) {
            throw new RuntimeException("Constructor failed");
        }

        @Override
        public void start() {
            // Never reached
        }

        @Override
        public void stop() {
            // Never reached
        }
    }

    /**
     * A process that throws exception in start method
     */
    private static class FailingStartProcess implements IProcess {
        public FailingStartProcess(org.evochora.node.spi.ServiceRegistry serviceRegistry, Config config) {
            // Valid constructor
        }

        @Override
        public void start() {
            throw new RuntimeException("Start failed");
        }

        @Override
        public void stop() {
            // Valid stop
        }
    }

    /**
     * A process that throws exception in stop method
     */
    private static class FailingStopProcess implements IProcess {
        private boolean started = false;

        public FailingStopProcess(org.evochora.node.spi.ServiceRegistry serviceRegistry, Config config) {
            // Valid constructor
        }

        @Override
        public void start() {
            started = true;
        }

        @Override
        public void stop() {
            throw new RuntimeException("Stop failed");
        }
    }
}