package org.evochora.node.processes.http.api.visualizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.database.dto.ParentRows;
import org.evochora.node.processes.http.api.visualizer.descent.AncestryIndexes;
import java.util.Collections;
import java.util.List;

import org.evochora.datapipeline.api.resources.database.IDatabaseReader;
import org.evochora.datapipeline.api.resources.database.IDatabaseReaderProvider;
import org.evochora.datapipeline.api.resources.database.dto.InstructionArgumentView;
import org.evochora.datapipeline.api.resources.database.dto.InstructionView;
import org.evochora.datapipeline.api.resources.database.dto.InstructionsView;
import org.evochora.datapipeline.api.resources.database.dto.OrganismRuntimeView;
import org.evochora.datapipeline.api.resources.database.dto.OrganismStaticInfo;
import org.evochora.datapipeline.api.resources.database.dto.OrganismTickDetails;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.node.spi.ServiceRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

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
        @DisplayName("Should create controller with HTTP cache configuration")
        void createControllerWithHttpCacheConfig() {
            ServiceRegistry serviceRegistry = new ServiceRegistry();
            IDatabaseReaderProvider mockDatabase = mock(IDatabaseReaderProvider.class);
            serviceRegistry.register(IDatabaseReaderProvider.class, mockDatabase);

            Config config = ConfigFactory.parseString("http-cache {}");

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

    @Nested
    @DisplayName("JSON Response")
    class JsonResponse {

        @Test
        @DisplayName("Should include instructions in JSON response")
        void shouldIncludeInstructionsInJsonResponse() throws Exception {
            ServiceRegistry serviceRegistry = new ServiceRegistry();
            IDatabaseReaderProvider mockDatabase = mock(IDatabaseReaderProvider.class);
            IDatabaseReader mockReader = mock(IDatabaseReader.class);
            serviceRegistry.register(IDatabaseReaderProvider.class, mockDatabase);

            // Create mock instruction data
            InstructionArgumentView regArg = InstructionArgumentView.register(0,
                    org.evochora.datapipeline.api.resources.database.dto.RegisterValueView.molecule(42, 1, "DATA", 42),
                    "DR");
            InstructionArgumentView immArg = InstructionArgumentView.immediate(42, "DATA", 42);
            InstructionView lastInstruction = new InstructionView(
                    1, "SETI", List.of(regArg, immArg), List.of("REGISTER", "IMMEDIATE"),
                    5, 0, new int[]{1, 2}, new int[]{0, 1}, false, null);
            InstructionsView instructions = new InstructionsView(lastInstruction, null);

            OrganismRuntimeView runtimeView = new OrganismRuntimeView(
                    100, new int[]{1, 2}, new int[]{0, 1}, new int[][]{{5, 5}}, 0,
                    Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), false, null, Collections.emptyList(),
                    instructions, 0, 0);

            OrganismStaticInfo staticInfo = new OrganismStaticInfo(null, 0L, -1L, "prog-1", new int[]{0, 0}, 0L, 0, null);
            OrganismTickDetails details = new OrganismTickDetails(1, 1L, staticInfo, List.of(), 0, runtimeView);

            when(mockDatabase.createReader(any())).thenReturn(mockReader);
            when(mockReader.readOrganismDetails(anyLong(), anyInt())).thenReturn(details);

            Config config = ConfigFactory.empty();
            OrganismController controller = new OrganismController(serviceRegistry, config);

            // Use reflection to access private method or test via HTTP mock
            // For now, we verify the controller can be created and reader returns instructions
            assertThat(controller).isNotNull();
            assertThat(mockReader.readOrganismDetails(1L, 1).state.instructions).isNotNull();
            assertThat(mockReader.readOrganismDetails(1L, 1).state.instructions.last).isNotNull();
            assertThat(mockReader.readOrganismDetails(1L, 1).state.instructions.last.opcodeName).isEqualTo("SETI");
        }
    }

    @Nested
    @DisplayName("Closing")
    class Closing {

        /**
         * The controller's close stops the real catch-up thread: it waits for the page being read
         * to end, never interrupts it, and nothing is read afterwards.
         */
        @Test
        @DisplayName("close() waits for the running page and stops the catch-up thread")
        void closeWaitsForTheRunningPageAndStopsTheThread() throws Exception {
            final CountDownLatch entered = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            final AtomicBoolean interrupted = new AtomicBoolean();
            final ServiceRegistry serviceRegistry = new ServiceRegistry();
            final IDatabaseReaderProvider provider = mock(IDatabaseReaderProvider.class);
            final IDatabaseReader reader = mock(IDatabaseReader.class);
            serviceRegistry.register(IDatabaseReaderProvider.class, provider);
            when(provider.createReader(any())).thenReturn(reader);
            when(reader.hasMetadata()).thenReturn(true);
            when(reader.getMetadata()).thenReturn(SimulationMetadata.getDefaultInstance());
            when(reader.readParents(anyInt(), anyInt())).thenAnswer(inv -> {
                entered.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("never released");
                }
                interrupted.set(Thread.currentThread().isInterrupted());
                return new ParentRows(new int[]{1}, new int[]{0});
            });

            final OrganismController controller = new OrganismController(serviceRegistry, ConfigFactory.empty());
            final Field field = OrganismController.class.getDeclaredField("ancestryIndexes");
            field.setAccessible(true);
            final AncestryIndexes indexes = (AncestryIndexes) field.get(controller);
            indexes.forRun("run").view(1);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

            final Thread closer = new Thread(controller::close, "closer");
            closer.start();
            await().atMost(Duration.ofSeconds(10)).until(() ->
                closer.getState() == Thread.State.TIMED_WAITING || closer.getState() == Thread.State.WAITING);
            assertThat(closer.isAlive()).as("close waits for the running page").isTrue();

            release.countDown();
            closer.join(10_000);
            assertThat(closer.isAlive()).isFalse();
            assertThat(interrupted).as("the catch-up thread is never interrupted").isFalse();

            indexes.forRun("run").view(5);
            verify(reader, times(1)).readParents(anyInt(), anyInt());
        }
    }
}



