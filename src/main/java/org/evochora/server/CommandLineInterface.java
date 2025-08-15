package org.evochora.server;

import org.evochora.server.engine.SimulationEngine;
import org.evochora.server.persistence.PersistenceService;
import org.evochora.server.queue.InMemoryTickQueue;
import org.evochora.server.queue.ITickMessageQueue;
import org.evochora.server.setup.Config;
import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple CLI REPL to control the simulation and persistence services.
 */
public final class CommandLineInterface {

    private static final Logger log = LoggerFactory.getLogger(CommandLineInterface.class);

    public static void main(String[] args) throws Exception {
        Config config = new Config();
        ITickMessageQueue queue = new InMemoryTickQueue(config);
        SimulationEngine sim = new SimulationEngine(queue);
        PersistenceService persist = new PersistenceService(queue, config);

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        log.info("Evochora server CLI ready. Commands: run | sim pause|resume | persist pause|resume | status | exit");

        while (true) {
            System.err.print(">>> ");
            String line = reader.readLine();
            if (line == null) break;
            String cmd = line.trim();
            switch (cmd) {
                case "run" -> {
                    // Compile and load initial artifacts from classpath prototypes
                    List<ProgramArtifact> artifacts = compileInitialArtifacts();
                    sim.setProgramArtifacts(artifacts);
                    if (!sim.isRunning()) sim.start();
                    if (!persist.isRunning()) persist.start();
                }
                case "sim pause" -> sim.pause();
                case "sim resume" -> sim.resume();
                case "persist pause" -> persist.pause();
                case "persist resume" -> persist.resume();
                case "status" -> {
                    System.err.printf("sim: running=%s paused=%s | persist: running=%s paused=%s | queueSize=%d%n",
                            sim.isRunning(), sim.isPaused(), persist.isRunning(), persist.isPaused(), queue.size());
                }
                case "exit" -> {
                    sim.shutdown();
                    persist.shutdown();
                    return;
                }
                default -> System.err.println("Unknown command");
            }
        }
    }

    private static List<ProgramArtifact> compileInitialArtifacts() {
        List<ProgramArtifact> out = new ArrayList<>();
        String base = "org/evochora/organism/prototypes/";
        String[] files = new String[] { "EnergySeeker.s", "InstructionTester.s" };
        Compiler compiler = new Compiler();
        for (String f : files) {
            String resourcePath = base + f;
            try (InputStream is = CommandLineInterface.class.getClassLoader().getResourceAsStream(resourcePath)) {
                if (is == null) {
                    log.warn("Prototype not found on classpath: {}", resourcePath);
                    continue;
                }
                try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    List<String> lines = br.lines().toList();
                    ProgramArtifact artifact = compiler.compile(lines, f);
                    out.add(artifact);
                }
            } catch (Exception e) {
                log.error("Failed to compile prototype {}", f, e);
            }
        }
        log.info("Compiled {} program artifact(s)", out.size());
        return out;
    }
}


