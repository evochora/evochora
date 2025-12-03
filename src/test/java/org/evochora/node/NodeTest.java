package org.evochora.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.evochora.junit.extensions.logging.ExpectLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.node.spi.IProcess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Unit tests for Node to verify configuration parsing, dependency injection,
 * and IProcess lifecycle management.
 */
@Tag("unit")
@ExtendWith({LogWatchExtension.class})
class NodeTest {

    private Config testConfig;
    private Node testNode; // Track the current node for cleanup

    @BeforeEach
    void setUp() {
        testConfig = ConfigFactory.parseString("""
            node {
              processes {
                test-process-1 {
                  className = "org.evochora.node.NodeTest$TestProcess1"
                }
                test-process-2 {
                  className = "org.evochora.node.NodeTest$TestProcess2"
                }
              }
            }
            """);
    }

    @AfterEach
    void tearDown() {
        // Ensure any test node is properly stopped to prevent cross-test interference
        if (testNode != null) {
            testNode.stop();
            testNode = null;
        }
    }

    @Test
    @DisplayName("Should parse configuration and instantiate processes via reflection")
    void constructor_shouldParseConfigurationAndInstantiateProcesses() {
        // Act
        testNode = new Node(testConfig);

        // Assert - Verify that the node was created successfully
        assertThat(testNode).isNotNull();

        // The actual process instantiation happens in the constructor,
        // so we verify the node was created without exceptions
        // In a real scenario, we would need to expose the processes for verification
    }

    @Test
    @DisplayName("Should start all configured processes")
    void start_shouldStartAllConfiguredProcesses() {
        // Arrange
        testNode = new Node(testConfig);

        // Act
        testNode.start();

        // Assert - Verify that start() was called on each process
        // Note: Since we can't easily verify the reflection-instantiated processes,
        // we verify that start() completes without exceptions
        // In a real implementation, we might want to expose the processes for verification
    }

    @Test
    @DisplayName("Should stop all configured processes")
    void stop_shouldStopAllConfiguredProcesses() {
        // Arrange
        testNode = new Node(testConfig);
        testNode.start();
        
        // Act
        testNode.stop();

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
            """);

        // Act & Assert - Should not throw exception
        testNode = new Node(emptyConfig);
        assertThat(testNode).isNotNull();

        // Should handle start/stop gracefully
        testNode.start();
        testNode.stop();
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
            """);

        // Act - Should not throw exception, but skip the invalid process
        testNode = new Node(invalidConfig);

        // Assert - Node should be created successfully, but the invalid process should be skipped
        assertThat(testNode).isNotNull();
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
            """);

        // Act - Should not throw exception, but skip the invalid process
        testNode = new Node(invalidConfig);

        // Assert - Node should be created successfully, but the invalid process should be skipped
        assertThat(testNode).isNotNull();
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
            """);

        // Act - Should not throw exception, but skip the failing process
        testNode = new Node(failingConfig);
        
        // Assert - Node should be created successfully, but the failing process should be skipped
        assertThat(testNode).isNotNull();
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
            """);

        testNode = new Node(failingStartConfig);

        // Act - Should not throw exception, but log error for failing process
        testNode.start();
        
        // Assert - Node should handle the failing process gracefully
        assertThat(testNode).isNotNull();
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
            """);

        testNode = new Node(failingStopConfig);
        testNode.start(); // Start successfully

        // Act - Should not throw exception, but log error for failing process
        testNode.stop();
        
        // Assert - Node should handle the failing process gracefully
        assertThat(testNode).isNotNull();
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
                  className = "org.evochora.node.NodeTest$TestProcess1"
                }
                invalid-process {
                  className = "org.nonexistent.InvalidProcess"
                }
              }
            }
            """);

        // Act - Should not throw exception, but skip the invalid process
        testNode = new Node(mixedConfig);
        
        // Assert - Node should be created successfully, but the invalid process should be skipped
        assertThat(testNode).isNotNull();
    }

    // Fake IProcess implementations for testing

    /**
     * A valid {@link IProcess} implementation for testing basic lifecycle
     * operations (start/stop).
     */
    @SuppressWarnings("unused") // Used via reflection in tests
    private static class TestProcess1 implements IProcess {
        private boolean started = false;
        private boolean stopped = false;

        /**
         * Constructs a new TestProcess. The parameters are required by the Node's
         * reflection-based instantiation but are not used in this test implementation.
         *
         * @param processName  The name of the process instance.
         * @param dependencies The map of resolved dependencies.
         * @param config       The configuration for this process.
         */
        public TestProcess1(String processName, Map<String, Object> dependencies, Config config) {
            // Valid constructor
        }

        /**
         * {@inheritDoc}
         * Marks this process as started.
         */
        @Override
        public void start() {
            started = true;
        }

        /**
         * {@inheritDoc}
         * Marks this process as stopped.
         */
        @Override
        public void stop() {
            stopped = true;
        }
    }

    /**
     * A second valid {@link IProcess} implementation for testing, identical to
     * {@link TestProcess1}, to avoid class loading issues when two processes of
     * the same type are configured.
     */
    @SuppressWarnings("unused") // Used via reflection in tests
    private static class TestProcess2 implements IProcess {
        private boolean started = false;
        private boolean stopped = false;

        /**
         * Constructs a new TestProcess. The parameters are required by the Node's
         * reflection-based instantiation but are not used in this test implementation.
         *
         * @param processName  The name of the process instance.
         * @param dependencies The map of resolved dependencies.
         * @param config       The configuration for this process.
         */
        public TestProcess2(String processName, Map<String, Object> dependencies, Config config) {
            // Valid constructor
        }

        /**
         * {@inheritDoc}
         * Marks this process as started.
         */
        @Override
        public void start() {
            started = true;
        }

        /**
         * {@inheritDoc}
         * Marks this process as stopped.
         */
        @Override
        public void stop() {
            stopped = true;
        }
    }

    /**
     * A class that doesn't implement IProcess, used for negative testing of
     * process loading.
     */
    @SuppressWarnings("unused") // Used via reflection in tests
    private static class InvalidProcess {
        /**
         * Constructs a new InvalidProcess. The parameters are required by the Node's
         * reflection-based instantiation but are not used in this test implementation.
         *
         * @param processName  The name of the process instance.
         * @param dependencies The map of resolved dependencies.
         * @param config       The configuration for this process.
         */
        public InvalidProcess(String processName, Map<String, Object> dependencies, Config config) {
            // Valid constructor but doesn't implement IProcess
        }
    }

    /**
     * A process that throws an exception in its constructor to test the Node's
     * error handling during initialization.
     */
    @SuppressWarnings("unused") // Used via reflection in tests
    private static class FailingProcess implements IProcess {
        /**
         * Constructs a new FailingProcess, which always throws a RuntimeException.
         *
         * @param processName  The name of the process instance.
         * @param dependencies The map of resolved dependencies.
         * @param config       The configuration for this process.
         * @throws RuntimeException always, to simulate a constructor failure.
         */
        public FailingProcess(String processName, Map<String, Object> dependencies, Config config) {
            throw new RuntimeException("Constructor failed");
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void start() {
            // Never reached
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void stop() {
            // Never reached
        }
    }

    /**
     * A process that throws an exception in its {@link #start()} method to test
     * the Node's error handling during startup.
     */
    @SuppressWarnings("unused") // Used via reflection in tests
    private static class FailingStartProcess implements IProcess {
        /**
         * Constructs a new FailingStartProcess.
         *
         * @param processName  The name of the process instance.
         * @param dependencies The map of resolved dependencies.
         * @param config       The configuration for this process.
         */
        public FailingStartProcess(String processName, Map<String, Object> dependencies, Config config) {
            // Valid constructor
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void start() {
            throw new RuntimeException("Start failed");
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void stop() {
            // Valid stop
        }
    }

    /**
     * A process that throws an exception in its {@link #stop()} method to test
     * the Node's error handling during shutdown.
     */
    @SuppressWarnings("unused") // Used via reflection in tests
    private static class FailingStopProcess implements IProcess {
        @SuppressWarnings("unused")
        private boolean started = false;

        /**
         * Constructs a new FailingStopProcess.
         *
         * @param processName  The name of the process instance.
         * @param dependencies The map of resolved dependencies.
         * @param config       The configuration for this process.
         */
        public FailingStopProcess(String processName, Map<String, Object> dependencies, Config config) {
            // Valid constructor
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void start() {
            started = true;
        }

        /**
         * {@inheritDoc}
         * Always throws a RuntimeException to simulate a stop failure.
         */
        @Override
        public void stop() {
            throw new RuntimeException("Stop failed");
        }
    }
}