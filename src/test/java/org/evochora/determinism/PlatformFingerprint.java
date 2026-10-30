package org.evochora.determinism;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.queues.StreamingBatch;
import org.evochora.datapipeline.resources.queues.InMemoryBlockingQueue;
import org.evochora.datapipeline.services.AbstractService;
import org.evochora.datapipeline.services.SimulationEngine;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;

/**
 * Runs the scenario of the platform comparison and writes a fingerprint of its course.
 * <p>
 * The build runs this program on every platform it tests and compares the fingerprints: a run with
 * the same seed and configuration has to take the same course on Linux, Windows and macOS. The
 * fingerprint holds one line per snapshot, its tick and a SHA-256 of the snapshot, so that two
 * fingerprints that differ name the first tick at which the platforms went apart. A snapshot holds
 * the whole state of the simulation: every occupied cell, every organism, the random state and the
 * state of every plugin. The run id and the capture time are cleared before hashing, because they
 * differ between any two runs and are not part of the simulation's course.
 * <p>
 * The scenario and its program lie under {@code src/test/resources/org/evochora/determinism/} and do
 * not depend on the shipped configuration or the shipped primordial.
 */
public final class PlatformFingerprint {

    private static final String RESOURCE_DIR = "/org/evochora/determinism/";

    private PlatformFingerprint() {
    }

    /**
     * Runs the scenario up to the given tick and writes its fingerprint.
     *
     * @param args the last tick to run and the file to write the fingerprint to
     * @throws Exception if the scenario cannot be read, the run fails, or the file cannot be written
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: PlatformFingerprint <last tick> <fingerprint file>");
        }
        long lastTick = Long.parseLong(args[0]);
        Path output = Path.of(args[1]);

        List<String> lines = run(scenario(lastTick), lastTick);
        Files.createDirectories(output.toAbsolutePath().getParent());
        // Every line ends in LF on every platform, so that the fingerprints compare byte for byte.
        Files.writeString(output, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        System.out.println("Fingerprint of " + lines.size() + " snapshots up to tick " + lastTick + " written to " + output);
    }

    /**
     * Reads the scenario and sets what depends on where it lies: the compiler's source roots, and
     * the tick the run pauses at.
     */
    private static Config scenario(long lastTick) throws URISyntaxException {
        Path root = Path.of(PlatformFingerprint.class.getResource(RESOURCE_DIR + "scenario.conf").toURI()).getParent();
        Config sourceRoots = ConfigFactory.parseMap(Map.of("compiler", Map.of("source-roots", List.of(
                Map.of("path", root.resolve("ancestor").toString()),
                Map.of("path", root.resolve("lib").toString(), "prefix", "LIB")))));
        return sourceRoots
                .withValue("pauseTicks", ConfigValueFactory.fromIterable(List.of(lastTick)))
                .withFallback(ConfigFactory.parseResources(PlatformFingerprint.class, "scenario.conf"))
                .resolve();
    }

    /**
     * Runs the engine until it pauses at the last tick and returns one line per snapshot it
     * published.
     */
    private static List<String> run(Config config, long lastTick) throws InterruptedException, NoSuchAlgorithmException {
        InMemoryBlockingQueue<TickDataChunk> tickData = new InMemoryBlockingQueue<>("fingerprint-ticks",
                ConfigFactory.parseMap(Map.of("capacity", 100)));
        InMemoryBlockingQueue<SimulationMetadata> metadata = new InMemoryBlockingQueue<>("fingerprint-metadata",
                ConfigFactory.parseMap(Map.of("capacity", 10)));
        Map<String, List<IResource>> resources = Map.of(
                "tickData", List.of(tickData),
                "metadataOutput", List.of(metadata));

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<String> lines = new ArrayList<>();
        SimulationEngine engine = new SimulationEngine("platform-fingerprint", config, resources);
        engine.start();
        try {
            boolean paused = false;
            while (true) {
                try (StreamingBatch<TickDataChunk> batch = tickData.receiveBatch(100, 1, TimeUnit.SECONDS)) {
                    for (TickDataChunk chunk : batch) {
                        lines.add(line(chunk.getSnapshot(), digest));
                    }
                    batch.commit();
                    if (batch.size() == 0 && paused) {
                        break;
                    }
                }
                // The state is read before the next batch is taken, so that a batch published
                // before the pause is drained before the loop ends.
                paused = engine.getCurrentState() == AbstractService.State.PAUSED;
                if (engine.getCurrentState() == AbstractService.State.ERROR) {
                    throw new IllegalStateException("The simulation engine failed before tick " + lastTick);
                }
            }
        } finally {
            engine.stop();
        }
        return lines;
    }

    /** One line of the fingerprint: the snapshot's tick and the hash of its state. */
    private static String line(TickData snapshot, MessageDigest digest) {
        TickData state = snapshot.toBuilder().clearSimulationRunId().clearCaptureTimeMs().build();
        return snapshot.getTickNumber() + " " + HexFormat.of().formatHex(digest.digest(state.toByteArray()));
    }
}
