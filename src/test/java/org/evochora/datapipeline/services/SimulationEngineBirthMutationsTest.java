package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.queues.StreamingBatch;
import org.evochora.datapipeline.resources.queues.InMemoryBlockingQueue;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The engine drops a newborn's birth mutation records once the recording that carries them is
 * captured — per recording, not per chunk.
 * <p>
 * A chunk is sent only once every few recordings. Records kept until the chunk is sent would be
 * captured into every recording of the chunk after the birth; records dropped before the states
 * of the recording are serialized would never be captured at all. Both mistakes leave every other
 * test green, so this one runs the engine over a birth with several recordings in the same chunk
 * and reads back where the records appear.
 */
@Tag("integration")
@AllowLog(level = LogLevel.INFO, loggerPattern = "org.evochora.datapipeline.services.SimulationEngine")
@AllowLog(level = LogLevel.INFO, loggerPattern = "org.evochora.datapipeline.services.AbstractService")
class SimulationEngineBirthMutationsTest {

    /** Recordings per chunk; the birth and the recordings after it must share one chunk. */
    private static final int RECORDINGS_PER_CHUNK = 8;

    /** The tick the run pauses at; three chunks, the birth falls into the second. */
    private static final long LAST_TICK = 3L * RECORDINGS_PER_CHUNK - 1;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    /**
     * A parent that reproduces once, around tick 10: it arms the marker register, idles on real
     * instructions until the fork tick, forks, and idles on. The child owns no cells; the birth
     * happens anyway, and that is all the recording plugin needs. The program is 39 cells long on
     * one row, so the world has to be wider than that or the tail wraps onto the head.
     */
    private static final String FORKING_PROGRAM = """
            START:
              SMRI DATA:5
              SETI %DR7 DATA:1^8
              FRKI 0|1 DATA:2000 1|0
            IDLE:
              SETI %DR7 DATA:2
              JMPI IDLE
            """;

    @Test
    void aNewbornCarriesItsRecordsInItsFirstRecordingOnly() throws IOException, InterruptedException {
        Path program = tempDir.resolve("fork_once.evo");
        Files.writeString(program, FORKING_PROGRAM);

        InMemoryBlockingQueue<TickDataChunk> tickDataQueue = new InMemoryBlockingQueue<>("tick-test",
                ConfigFactory.parseMap(Map.of("capacity", 100)));
        InMemoryBlockingQueue<SimulationMetadata> metadataQueue = new InMemoryBlockingQueue<>("meta-test",
                ConfigFactory.parseMap(Map.of("capacity", 10)));
        Map<String, List<IResource>> resources = new HashMap<>();
        resources.put("tickData", Collections.singletonList(tickDataQueue));
        resources.put("metadataOutput", Collections.singletonList(metadataQueue));

        Config config = ConfigFactory.parseMap(Map.of(
                "samplingInterval", 1,
                "accumulatedDeltaInterval", 1,
                "snapshotInterval", 1,
                "chunkInterval", RECORDINGS_PER_CHUNK,
                "pauseTicks", List.of(LAST_TICK),
                "environment", Map.of("shape", List.of(64, 32), "topology", "TORUS"),
                "organisms", List.of(Map.of(
                        "program", program.toString(),
                        "initialEnergy", 10000,
                        "placement", Map.of("positions", List.of(5, 5)))),
                "plugins", List.of(Map.of(
                        "className", RecordingBirthHandlerTestPlugin.class.getName(),
                        "options", Map.of())),
                "seed", 12345L,
                "runtime", Map.of(
                        "organism", Map.of("max-energy", 32767, "max-entropy", 8191, "error-penalty-cost", 500),
                        "thermodynamics", Map.of(
                                "default", Map.of(
                                        "className", "org.evochora.runtime.thermodynamics.impl.UniversalThermodynamicPolicy",
                                        "options", Map.of("base-energy", 1, "base-entropy", 1)),
                                "overrides", Map.of("instructions", Map.of(), "families", Map.of())))
        ));

        SimulationEngine engine = new SimulationEngine("test-engine", config, resources);
        engine.start();
        try {
            await().atMost(10, TimeUnit.SECONDS)
                    .untilAsserted(() -> assertThat(engine.getCurrentState())
                            .isEqualTo(AbstractService.State.PAUSED));
        } finally {
            engine.stop();
        }

        // Every recording of the run, with the chunk it was sent in, in tick order.
        List<Recording> recordings = new ArrayList<>();
        int chunkIndex = 0;
        while (true) {
            try (StreamingBatch<TickDataChunk> batch = tickDataQueue.receiveBatch(100, 100, TimeUnit.MILLISECONDS)) {
                if (batch.size() == 0) {
                    break;
                }
                for (TickDataChunk chunk : batch) {
                    TickData snapshot = chunk.getSnapshot();
                    recordings.add(new Recording(chunkIndex, snapshot.getTickNumber(), snapshot.getOrganismsList()));
                    for (TickDelta delta : chunk.getDeltasList()) {
                        recordings.add(new Recording(chunkIndex, delta.getTickNumber(), delta.getOrganismsList()));
                    }
                    chunkIndex++;
                }
                batch.commit();
            }
        }
        assertThat(recordings).hasSize((int) LAST_TICK + 1);

        // The child's recordings: the ones that carry a child state, in tick order.
        List<Recording> childRecordings = recordings.stream()
                .filter(recording -> recording.child() != null)
                .toList();
        assertThat(childRecordings).as("the parent forks once and the child is recorded").isNotEmpty();

        Recording first = childRecordings.get(0);
        assertThat(first.child().getBirthMutationsList())
                .as("the first recording of the child carries what the birth handler reported")
                .hasSize(1);
        assertThat(first.child().getBirthMutations(0).getKind()).isEqualTo(RecordingBirthHandlerTestPlugin.KIND);

        List<Recording> later = childRecordings.subList(1, childRecordings.size());
        assertThat(later.stream().filter(recording -> recording.chunkIndex() == first.chunkIndex()).count())
                .as("later recordings of the child in the same chunk, without which the test proves nothing")
                .isGreaterThanOrEqualTo(2);
        for (Recording recording : later) {
            assertThat(recording.child().getBirthMutationsList())
                    .as("tick %d repeats the records of the birth", recording.tick())
                    .isEmpty();
        }
    }

    /** One recording: the chunk it travelled in, its tick, and the child's state if it has one. */
    private record Recording(int chunkIndex, long tick, OrganismState child) {

        Recording(int chunkIndex, long tick, List<OrganismState> organisms) {
            this(chunkIndex, tick, organisms.stream()
                    .filter(OrganismState::hasParentId)
                    .findFirst()
                    .orElse(null));
        }
    }
}
