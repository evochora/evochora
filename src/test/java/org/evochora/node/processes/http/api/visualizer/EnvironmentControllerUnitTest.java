/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

package org.evochora.node.processes.http.api.visualizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import org.evochora.datapipeline.api.resources.database.IDatabaseReader;
import org.evochora.datapipeline.api.resources.database.IDatabaseReaderProvider;
import org.evochora.datapipeline.api.resources.database.dto.ChunkIndexSummary;
import org.evochora.datapipeline.api.resources.database.dto.SampledTickRange;
import org.evochora.datapipeline.api.resources.database.dto.SpatialRegion;
import org.evochora.datapipeline.api.resources.database.dto.TickRangeExtension;
import org.evochora.node.processes.http.api.visualizer.dto.TickRangesResponseDto;
import org.evochora.node.spi.ServiceRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Unit tests for HTTP request parsing and controller construction (no database I/O).
 * <p>
 * Tests focus on data classes and controller construction without database dependencies:
 * <ul>
 *   <li>SpatialRegion parsing and validation</li>
 *   <li>Controller construction and configuration</li>
 * </ul>
 * <p>
 * <strong>Note:</strong> CellWithCoordinates tests were removed as the API now returns
 * Protobuf binary instead of JSON. See EnvironmentControllerIntegrationTest for 
 * Protobuf response validation.
 * <p>
 * <strong>AGENTS.md Compliance:</strong>
 * <ul>
 *   <li>Tagged as @Tag("unit") - <0.2s runtime, no I/O</li>
 *   <li>No database dependencies - pure unit tests</li>
 *   <li>Inline test data - all test data constructed inline</li>
 *   <li>Fast execution - no external dependencies</li>
 * </ul>
 */
@Tag("unit")
@DisplayName("EnvironmentController Unit Tests")
class EnvironmentControllerUnitTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Nested
    @DisplayName("Region Parsing")
    class RegionParsing {

        @Test
        @DisplayName("Should parse 2D region correctly")
        void parse2DRegion_correctly() {
            SpatialRegion region = createSpatialRegion(new int[]{0, 50, 0, 50});
            
            assertThat(region).isNotNull();
            assertThat(region.getDimensions()).isEqualTo(2);
            assertThat(region.bounds).isEqualTo(new int[]{0, 50, 0, 50});
        }

        @Test
        @DisplayName("Should parse 3D region correctly")
        void parse3DRegion_correctly() {
            SpatialRegion region = createSpatialRegion(new int[]{0, 100, 0, 100, 0, 50});
            
            assertThat(region).isNotNull();
            assertThat(region.getDimensions()).isEqualTo(3);
            assertThat(region.bounds).isEqualTo(new int[]{0, 100, 0, 100, 0, 50});
        }

        @Test
        @DisplayName("Should parse 4D region correctly")
        void parse4DRegion_correctly() {
            SpatialRegion region = createSpatialRegion(new int[]{0, 10, 0, 10, 0, 5, 0, 5});
            
            assertThat(region).isNotNull();
            assertThat(region.getDimensions()).isEqualTo(4);
            assertThat(region.bounds).isEqualTo(new int[]{0, 10, 0, 10, 0, 5, 0, 5});
        }

        @Test
        @DisplayName("Should throw exception for odd number of coordinates")
        void oddNumberOfCoordinates_throwsException() {
            int[] bounds = new int[]{1, 2, 3};
            
            assertThatThrownBy(() -> createSpatialRegion(bounds))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("even number of values");
        }

        @Test
        @DisplayName("Should handle negative coordinates")
        void negativeCoordinates_handledCorrectly() {
            SpatialRegion region = createSpatialRegion(new int[]{-10, 10, -5, 5});
            
            assertThat(region).isNotNull();
            assertThat(region.getDimensions()).isEqualTo(2);
            assertThat(region.bounds).isEqualTo(new int[]{-10, 10, -5, 5});
        }

        @Test
        @DisplayName("Should handle large coordinates")
        void largeCoordinates_handledCorrectly() {
            SpatialRegion region = createSpatialRegion(new int[]{0, 1000000, 0, 1000000});
            
            assertThat(region).isNotNull();
            assertThat(region.getDimensions()).isEqualTo(2);
            assertThat(region.bounds).isEqualTo(new int[]{0, 1000000, 0, 1000000});
        }
    }

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

            EnvironmentController testController = new EnvironmentController(serviceRegistry, config);

            assertThat(testController).isNotNull();
        }

        @Test
        @DisplayName("Should create controller with default configuration")
        void createControllerWithDefaultConfiguration() {
            ServiceRegistry serviceRegistry = new ServiceRegistry();
            IDatabaseReaderProvider mockDatabase = mock(IDatabaseReaderProvider.class);
            serviceRegistry.register(IDatabaseReaderProvider.class, mockDatabase);

            EnvironmentController controllerWithDefault =
                new EnvironmentController(serviceRegistry, ConfigFactory.empty());

            assertThat(controllerWithDefault).isNotNull();
        }
    }

    @Nested
    @DisplayName("Error Bodies")
    class ErrorBodies {

        @Test
        @DisplayName("Should name the log line when the client is told nothing else")
        void carriesTheReferenceOfTheLogLine() {
            EnvironmentController controller = controllerWithMockedDatabase();

            Map<String, Object> body = controller.createErrorBody(
                HttpStatus.INTERNAL_SERVER_ERROR, "An internal server error occurred", "a1b2c3d4");

            assertThat(body).containsEntry("reference", "a1b2c3d4");
            assertThat(body).containsEntry("status", 500);
        }

        @Test
        @DisplayName("Should leave out the reference where the message already says what happened")
        void omitsTheReferenceWhenThereIsNothingToLookUp() {
            EnvironmentController controller = controllerWithMockedDatabase();

            Map<String, Object> body = controller.createErrorBody(
                HttpStatus.BAD_REQUEST, "Invalid tick number: abc");

            assertThat(body).doesNotContainKey("reference");
            assertThat(body).containsEntry("message", "Invalid tick number: abc");
        }

        private EnvironmentController controllerWithMockedDatabase() {
            ServiceRegistry serviceRegistry = new ServiceRegistry();
            serviceRegistry.register(IDatabaseReaderProvider.class, mock(IDatabaseReaderProvider.class));
            return new EnvironmentController(serviceRegistry, ConfigFactory.empty());
        }
    }

    @Nested
    @DisplayName("Recorded Ticks")
    class RecordedTicks {

        private static final String RUN_ID = "test_run";

        @Test
        @DisplayName("Should report the ranges a viewer can navigate along")
        void reportsTheRangesOfTheRun() throws Exception {
            IDatabaseReader reader = readerWith(
                new ChunkIndexSummary(3L, 290L, 30L),
                extensionOf(List.of(new SampledTickRange(100L, 190L, 10L),
                                    new SampledTickRange(250L, 290L, 20L)), 3L, 30L, 250L));
            EnvironmentController controller = controllerReading(reader);

            String json = objectMapper.writeValueAsString(callGetTicks(controller));

            assertThat(json).contains("\"minTick\":100");
            assertThat(json).contains("\"maxTick\":290");
            assertThat(json).contains("{\"first\":100,\"last\":190,\"step\":10}");
            assertThat(json).contains("{\"first\":250,\"last\":290,\"step\":20}");
        }

        @Test
        @DisplayName("Should keep the ranges while the chunk index stands still")
        void readsTheIndexOnlyOnce() throws Exception {
            IDatabaseReader reader = readerWith(
                new ChunkIndexSummary(2L, 190L, 20L),
                extensionOf(List.of(new SampledTickRange(0L, 190L, 10L)), 2L, 20L, 100L));
            EnvironmentController controller = controllerReading(reader);

            callGetTicks(controller);
            TickRangesResponseDto second = callGetTicks(controller);

            verify(reader, times(2)).getChunkIndexSummary();
            verify(reader, times(1)).getTickRanges();
            verify(reader, never()).extendTickRanges(anyList(), anyLong());
            assertThat(second.ranges()).containsExactly(new SampledTickRange(0L, 190L, 10L));
        }

        @Test
        @DisplayName("Should read only the new chunks once the index has grown")
        void readsOnlyWhatWasAppended() throws Exception {
            IDatabaseReader reader = mock(IDatabaseReader.class);
            when(reader.getChunkIndexSummary())
                .thenReturn(new ChunkIndexSummary(2L, 190L, 20L))
                .thenReturn(new ChunkIndexSummary(3L, 290L, 30L));
            when(reader.getTickRanges()).thenReturn(
                extensionOf(List.of(new SampledTickRange(0L, 190L, 10L)), 2L, 20L, 100L));
            when(reader.extendTickRanges(anyList(), anyLong())).thenReturn(
                extensionOf(List.of(new SampledTickRange(0L, 290L, 10L)), 1L, 10L, 200L));
            EnvironmentController controller = controllerReading(reader);

            callGetTicks(controller);
            TickRangesResponseDto second = callGetTicks(controller);

            verify(reader, times(1)).getTickRanges();
            verify(reader).extendTickRanges(List.of(new SampledTickRange(0L, 190L, 10L)), 100L);
            assertThat(second.maxTick()).isEqualTo(290L);
        }

        @Test
        @DisplayName("Should build the ranges anew when a chunk that was already there has changed")
        void readsEverythingAgainWhenTheGrowthDoesNotAddUp() throws Exception {
            IDatabaseReader reader = mock(IDatabaseReader.class);
            // The second summary holds five ticks more without holding another chunk: one of the
            // two known chunks was written again
            when(reader.getChunkIndexSummary())
                .thenReturn(new ChunkIndexSummary(2L, 190L, 20L))
                .thenReturn(new ChunkIndexSummary(2L, 190L, 25L));
            when(reader.getTickRanges())
                .thenReturn(extensionOf(List.of(new SampledTickRange(0L, 190L, 10L)), 2L, 20L, 100L))
                .thenReturn(extensionOf(List.of(new SampledTickRange(0L, 190L, 5L)), 2L, 25L, 100L));
            when(reader.extendTickRanges(anyList(), anyLong())).thenReturn(
                extensionOf(List.of(new SampledTickRange(0L, 190L, 10L)), 0L, 0L, 100L));
            EnvironmentController controller = controllerReading(reader);

            callGetTicks(controller);
            TickRangesResponseDto second = callGetTicks(controller);

            verify(reader, times(2)).getTickRanges();
            assertThat(second.ranges()).containsExactly(new SampledTickRange(0L, 190L, 5L));
        }

        @Test
        @DisplayName("Should carry the ticks the index holds in the ETag")
        void namesTheSampleCountInTheETag() throws Exception {
            IDatabaseReader reader = mock(IDatabaseReader.class);
            when(reader.getChunkIndexSummary())
                .thenReturn(new ChunkIndexSummary(2L, 190L, 20L))
                .thenReturn(new ChunkIndexSummary(2L, 190L, 25L));
            when(reader.getTickRanges()).thenReturn(
                extensionOf(List.of(new SampledTickRange(0L, 190L, 10L)), 2L, 20L, 100L));
            when(reader.extendTickRanges(anyList(), anyLong())).thenReturn(
                extensionOf(List.of(new SampledTickRange(0L, 190L, 10L)), 0L, 0L, 100L));
            Config withETag = ConfigFactory.parseString(
                "http-cache { ticks { enabled = true, maxAge = 5, useETag = true } }");
            EnvironmentController controller = controllerReading(reader, withETag);

            String first = callGetTicksForETag(controller);
            String second = callGetTicksForETag(controller);

            assertThat(first).contains("_20");
            assertThat(second).contains("_25");
            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("Should answer 404 while the run has recorded nothing")
        void reportsNoRunWhileNothingIsRecorded() throws Exception {
            IDatabaseReader reader = mock(IDatabaseReader.class);
            when(reader.getChunkIndexSummary()).thenReturn(new ChunkIndexSummary(0L, 0L, 0L));
            EnvironmentController controller = controllerReading(reader);

            assertThatThrownBy(() -> controller.getTicks(contextForRun()))
                .isInstanceOf(VisualizerBaseController.NoRunIdException.class);
            verify(reader, never()).getTickRanges();
        }

        private TickRangeExtension extensionOf(List<SampledTickRange> ranges, long addedChunks,
                                               long addedSamples, long lastFirstTick) {
            return new TickRangeExtension(ranges, addedChunks, addedSamples, lastFirstTick);
        }

        private IDatabaseReader readerWith(ChunkIndexSummary summary, TickRangeExtension ranges)
                throws Exception {
            IDatabaseReader reader = mock(IDatabaseReader.class);
            when(reader.getChunkIndexSummary()).thenReturn(summary);
            when(reader.getTickRanges()).thenReturn(ranges);
            return reader;
        }

        private EnvironmentController controllerReading(IDatabaseReader reader) throws Exception {
            return controllerReading(reader, ConfigFactory.empty());
        }

        private EnvironmentController controllerReading(IDatabaseReader reader, Config options)
                throws Exception {
            ServiceRegistry serviceRegistry = new ServiceRegistry();
            IDatabaseReaderProvider provider = mock(IDatabaseReaderProvider.class);
            when(provider.createReader(RUN_ID)).thenReturn(reader);
            serviceRegistry.register(IDatabaseReaderProvider.class, provider);
            return new EnvironmentController(serviceRegistry, options);
        }

        private Context contextForRun() {
            Context ctx = mock(Context.class);
            when(ctx.queryParam("runId")).thenReturn(RUN_ID);
            when(ctx.status(HttpStatus.OK)).thenReturn(ctx);
            return ctx;
        }

        private TickRangesResponseDto callGetTicks(EnvironmentController controller) throws Exception {
            Context ctx = contextForRun();
            controller.getTicks(ctx);

            ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
            verify(ctx).json(body.capture());
            return (TickRangesResponseDto) body.getValue();
        }

        private String callGetTicksForETag(EnvironmentController controller) throws Exception {
            Context ctx = contextForRun();
            controller.getTicks(ctx);

            ArgumentCaptor<String> etag = ArgumentCaptor.forClass(String.class);
            verify(ctx).header(eq("ETag"), etag.capture());
            return etag.getValue();
        }
    }

    @Nested
    @DisplayName("SpatialRegion Serialization")
    class SpatialRegionSerialization {

        @Test
        @DisplayName("Should serialize SpatialRegion correctly")
        void serializeSpatialRegion_correctly() throws Exception {
            SpatialRegion region = createSpatialRegion(new int[]{0, 100, 0, 50});
            
            String json = objectMapper.writeValueAsString(region);
            
            assertThat(json).contains("\"bounds\":[0,100,0,50]");
        }
    }

    @Nested
    @DisplayName("Data Class Validation")
    class DataClassValidation {

        @Test
        @DisplayName("Should validate SpatialRegion with negative coordinates")
        void validateSpatialRegionWithNegativeCoordinates() {
            SpatialRegion region = createSpatialRegion(new int[]{-10, 10, -5, 5});
            
            assertThat(region).isNotNull();
            assertThat(region.getDimensions()).isEqualTo(2);
            assertThat(region.bounds).isEqualTo(new int[]{-10, 10, -5, 5});
        }

        @Test
        @DisplayName("Should validate SpatialRegion with large coordinates")
        void validateSpatialRegionWithLargeCoordinates() {
            SpatialRegion region = createSpatialRegion(new int[]{0, 1000000, 0, 1000000});
            
            assertThat(region).isNotNull();
            assertThat(region.getDimensions()).isEqualTo(2);
            assertThat(region.bounds).isEqualTo(new int[]{0, 1000000, 0, 1000000});
        }
    }

    // Helper method for creating test data
    private SpatialRegion createSpatialRegion(int[] bounds) {
        return new SpatialRegion(bounds);
    }
}
