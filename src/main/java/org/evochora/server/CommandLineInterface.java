package org.evochora.server;

import org.evochora.server.engine.SimulationEngine;
import org.evochora.server.persistence.PersistenceService;
import org.evochora.server.queue.InMemoryTickQueue;
import org.evochora.server.queue.ITickMessageQueue;
import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.server.engine.StatusMetricsRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Simple CLI REPL to control the simulation and persistence services.
 */
public final class CommandLineInterface {

    private static final Logger log = LoggerFactory.getLogger(CommandLineInterface.class);

    public static void main(String[] args) throws Exception {
        ITickMessageQueue queue = new InMemoryTickQueue();
        SimulationEngine sim = null;
        PersistenceService persist = null;
        java.util.ArrayList<ProgramArtifact> loadedArtifacts = new java.util.ArrayList<>();

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        log.info("Evochora server CLI ready. Commands: load <file> | run | run debug | status | exit");

        while (true) {
            System.err.print(">>> ");
            String line = reader.readLine();
            if (line == null) break;
            String cmd = line.trim();
            if (cmd.startsWith("load ")) {
                String path = cmd.substring("load ".length()).trim();
                if (path.isEmpty()) {
                    System.err.println("Usage: load <assembly_file_path>");
                    continue;
                }
                if (sim != null && (sim.isRunning() || persist.isRunning())) {
                    System.err.println("Stop services before loading a new program (use 'exit' then 'run').");
                    continue;
                }
                try {
                    List<String> lines;
                    int[] startPos = null;
                    // Optional coordinates parsing: e.g., "load file.s 10|20". Coordinates represent
                    // both the placement origin for machine code and the organism start position.
                    String[] parts = path.split("\\s+");
                    String fileToken = parts[0];
                    if (parts.length > 1) {
                        String coordToken = parts[1];
                        String[] comps = coordToken.split("\\|");
                        if (comps.length != org.evochora.runtime.Config.WORLD_DIMENSIONS) {
                            System.err.printf("Invalid coordinates, expected %d dimensions separated by '|'%n", org.evochora.runtime.Config.WORLD_DIMENSIONS);
                            continue;
                        }
                        startPos = new int[comps.length];
                        for (int i = 0; i < comps.length; i++) {
                            startPos[i] = Integer.parseInt(comps[i]);
                        }
                    }
                    String resourceBaseSingular = "org/evochora/organism/prototype/";
                    String resourceBasePlural = "org/evochora/organism/prototypes/";
                    String resourcePath = resourceBaseSingular + fileToken;
                    java.io.InputStream is = CommandLineInterface.class.getClassLoader().getResourceAsStream(resourcePath);
                    if (is == null) {
                        resourcePath = resourceBasePlural + fileToken;
                        is = CommandLineInterface.class.getClassLoader().getResourceAsStream(resourcePath);
                    }
                    if (is != null) {
                        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is, StandardCharsets.UTF_8))) {
                            lines = br.lines().toList();
                        }
                    } else {
                        // Fallback: treat as filesystem path
                        lines = Files.readAllLines(Path.of(path), StandardCharsets.UTF_8);
                    }
                    Compiler compiler = new Compiler();
                    ProgramArtifact artifact = compiler.compile(lines, Path.of(fileToken).getFileName().toString());
                    if (startPos != null) {
                        // Remember desired start so engine places code and organism starting there
                        org.evochora.server.engine.UserLoadRegistry.registerDesiredStart(artifact.programId(), startPos);
                    }
                    loadedArtifacts.add(artifact);
                    System.err.printf("Loaded program '%s' (programId=%s). Loaded count=%d.%n", fileToken, artifact.programId(), loadedArtifacts.size());
                } catch (Exception e) {
                    log.error("Failed to compile {}", path, e);
                }
                continue;
            } else if (cmd.equalsIgnoreCase("run") || cmd.equalsIgnoreCase("run debug")) {
                boolean performanceMode = !cmd.equalsIgnoreCase("run debug");

                if (performanceMode) {
                    log.info("Starting in PERFORMANCE mode.");
                } else {
                    log.info("Starting in DEBUG mode.");
                }

                sim = new SimulationEngine(queue, performanceMode);
                persist = new PersistenceService(queue, performanceMode);

                sim.setProgramArtifacts(java.util.List.copyOf(loadedArtifacts));
                if (!sim.isRunning()) { sim.start(); System.err.println("SimulationEngine started."); }
                if (!persist.isRunning()) { persist.start(); System.err.println("PersistenceService started."); }
                continue;
            }
            switch (cmd) {
                case "sim pause" -> { if(sim != null) sim.pause(); System.err.println("SimulationEngine paused."); }
                case "sim resume" -> { if(sim != null) sim.resume(); System.err.println("SimulationEngine resumed."); }
                case "persist pause" -> { if(persist != null) persist.pause(); System.err.println("PersistenceService paused."); }
                case "persist resume" -> { if(persist != null) persist.resume(); System.err.println("PersistenceService resumed."); }
                case "status" -> {
                    long simTick = (sim != null) ? sim.getCurrentTick() : -1L;
                    long persistTick = (persist != null) ? persist.getLastPersistedTick() : -1L;
                    int[] orgCounts = (sim != null) ? sim.getOrganismCounts() : new int[]{0,0};
                    double tps = StatusMetricsRegistry.getTicksPerSecond();
                    System.err.printf(
                            "sim: %s | persist: %s | ticks (persist/sim): %d/%d | organisms (living/dead): %d/%d | queue: %d | tps: %.2f%n",
                            (sim != null && sim.isPaused()) ? "paused" : ((sim != null && sim.isRunning()) ? "running" : "stopped"),
                            (persist != null && persist.isPaused()) ? "paused" : ((persist != null && persist.isRunning()) ? "running" : "stopped"),
                            persistTick, simTick, orgCounts[0], orgCounts[1], queue.size(), tps);
                }
                case "exit" -> {
                    if (sim != null) sim.shutdown();
                    if (persist != null) persist.shutdown();
                    System.err.println("Services stopped. Exiting.");
                    return;
                }
                default -> System.err.println("Unknown command");
            }
        }
    }
}