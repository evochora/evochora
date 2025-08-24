package org.evochora.server;

import org.evochora.runtime.isa.Instruction;
import org.evochora.server.engine.SimulationEngine;
import org.evochora.server.config.ConfigLoader;
import org.evochora.server.config.SimulationConfiguration;
import org.evochora.server.persistence.PersistenceService;
import org.evochora.server.queue.InMemoryTickQueue;
import org.evochora.server.queue.ITickMessageQueue;
// Removed unused imports
import org.evochora.server.engine.StatusMetricsRegistry;
// import org.evochora.server.engine.WorldStateAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.evochora.server.http.AnalysisWebService;

import java.io.BufferedReader;
import java.io.InputStreamReader;
// Removed unused imports

/**
 * Simple CLI REPL to control the simulation and persistence services.
 */
public final class CommandLineInterface {

    private static final Logger log = LoggerFactory.getLogger(CommandLineInterface.class);

    public static void main(String[] args) throws Exception {
        Instruction.init();

        ITickMessageQueue queue = new InMemoryTickQueue();
        SimulationEngine sim = null;
        PersistenceService persist = null;
        AnalysisWebService web = null;
        // 'load' command removed; programs are defined in config
        SimulationConfiguration loadedConfig = null;

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        log.info("Evochora server CLI ready. Commands: config <file> | run <file?> | run debug <file?> | view [db] | tick [n] | sim pause|resume | persist pause|resume | web stop | status | clear | exit");

        while (true) {
            System.err.print(">>> ");
            String line = reader.readLine();
            if (line == null) break;
            String cmd = line.trim();
            if (cmd.startsWith("config ")) {
                String path = cmd.substring("config ".length()).trim();
                if (path.isEmpty()) {
                    System.err.println("Usage: config <filepath>");
                    continue;
                }
                try {
                    loadedConfig = org.evochora.server.config.ConfigLoader.loadFromFile(java.nio.file.Path.of(path));
                    System.err.printf("Loaded config from %s: dims=%d shape=[%s] toroidal=%s%n",
                            path,
                            loadedConfig.getDimensions(),
                            java.util.Arrays.stream(loadedConfig.environment.shape).mapToObj(String::valueOf).collect(java.util.stream.Collectors.joining(",")),
                            loadedConfig.environment.toroidal);
                } catch (Exception e) {
                    System.err.println("Failed to load config: " + e.getMessage());
                }
                continue;
            } else if (cmd.startsWith("run debug")) {
                boolean performanceMode = false;

                if (performanceMode) {
                    log.info("Starting in PERFORMANCE mode.");
                } else {
                    log.info("Starting in DEBUG mode.");
                }
                SimulationConfiguration cfg;
                String[] partsRunDbg = cmd.split("\\s+", 3);
                if (partsRunDbg.length >= 3) {
                    try {
                        cfg = ConfigLoader.loadFromFile(java.nio.file.Path.of(partsRunDbg[2]));
                    } catch (Exception e) {
                        System.err.println("Failed to load config: " + e.getMessage());
                        continue;
                    }
                } else {
                    cfg = loadedConfig != null ? loadedConfig : ConfigLoader.loadDefault();
                    if (loadedConfig == null) {
                        System.err.println("No config provided. Using default resources config.json.");
                    }
                }

                sim = new SimulationEngine(queue, performanceMode, cfg.environment.shape, cfg.environment.toroidal);
                // Configure deterministic randomness if seed is provided
                sim.setSeed(cfg.seed);
                persist = new PersistenceService(queue, performanceMode, cfg.environment.shape);

                sim.setOrganismDefinitions(cfg.organisms);
                // Configure energy strategies from config if provided
                sim.setEnergyStrategies(cfg.energyStrategies);
                if (!sim.isRunning()) { sim.start(); System.err.println("SimulationEngine started."); }
                if (!persist.isRunning()) { persist.start(); System.err.println("PersistenceService started."); }
                // Also start web service in debug mode
                try {
                    int port = (cfg.web != null && cfg.web.port != null) ? cfg.web.port : 7070;
                    java.nio.file.Path dbPath = persist.getDbFilePath();
                    if (dbPath != null) {
                        if (web != null) { web.stop(); }
                        web = new AnalysisWebService();
                        web.start(dbPath.toString(), port);
                        System.err.println("AnalysisWebService at http://localhost:" + port);
                    }
                } catch (Exception we) {
                    System.err.println("Failed to start web service: " + we.getMessage());
                }
                continue;
            } else if (cmd.startsWith("run")) {
                boolean performanceMode = true;

                if (performanceMode) {
                    log.info("Starting in PERFORMANCE mode.");
                } else {
                    log.info("Starting in DEBUG mode.");
                }
                SimulationConfiguration cfg;
                String[] partsRun = cmd.split("\\s+", 2);
                if (partsRun.length >= 2) {
                    try {
                        cfg = ConfigLoader.loadFromFile(java.nio.file.Path.of(partsRun[1]));
                    } catch (Exception e) {
                        System.err.println("Failed to load config: " + e.getMessage());
                        continue;
                    }
                } else {
                    cfg = loadedConfig != null ? loadedConfig : ConfigLoader.loadDefault();
                    if (loadedConfig == null) {
                        System.err.println("No config provided. Using default resources config.json.");
                    }
                }

                sim = new SimulationEngine(queue, performanceMode, cfg.environment.shape, cfg.environment.toroidal);
                sim.setSeed(cfg.seed);
                persist = new PersistenceService(queue, performanceMode, cfg.environment.shape);
                sim.setOrganismDefinitions(cfg.organisms);
                sim.setEnergyStrategies(cfg.energyStrategies);
                if (!sim.isRunning()) { sim.start(); System.err.println("SimulationEngine started."); }
                if (!persist.isRunning()) { persist.start(); System.err.println("PersistenceService started."); }
                // Start web in performance mode as well (serves reduced data)
                try {
                    int port = (cfg.web != null && cfg.web.port != null) ? cfg.web.port : 7070;
                    java.nio.file.Path dbPath = persist.getDbFilePath();
                    if (dbPath != null) {
                        if (web != null) { web.stop(); }
                        web = new AnalysisWebService();
                        web.start(dbPath.toString(), port);
                        System.err.println("AnalysisWebService at http://localhost:" + port);
                    }
                } catch (Exception we) {
                    System.err.println("Failed to start web service: " + we.getMessage());
                }
                continue;
            } else if (cmd.startsWith("tick")) {
                if (sim == null || persist == null) {
                    // Boot minimal debug services if not running
                    boolean performanceMode = false;
                    sim = new SimulationEngine(queue, performanceMode);
                    persist = new PersistenceService(queue, performanceMode);
                    sim.start();
                    persist.start();
                    // Pause immediately to gain control over stepping
                    sim.pause();
                } else {
                    // Ensure paused before stepping
                    sim.pause();
                }
                int n = 1;
                String[] parts = cmd.split("\\s+");
                if (parts.length > 1) {
                    try { n = Integer.parseInt(parts[1]); } catch (NumberFormatException ignore) { n = 1; }
                }
                for (int i = 0; i < n; i++) {
                    sim.step();
                }
                System.err.printf("Stepped %d tick(s). Current: %d%n", n, sim.getCurrentTick());
                continue;
            }
            switch (cmd) {
                case "sim pause" -> { if(sim != null) sim.pause(); System.err.println("SimulationEngine paused."); }
                case "sim resume" -> { if(sim != null) sim.resume(); System.err.println("SimulationEngine resumed."); }
                case "persist pause" -> { if(persist != null) persist.pause(); System.err.println("PersistenceService paused."); }
                case "persist resume" -> { if(persist != null) persist.resume(); System.err.println("PersistenceService resumed."); }
                case "web stop" -> { if (web != null) { web.stop(); web = null; System.err.println("Web service stopped."); } }
                case "view" -> {
                    try {
                        // Determine DB path
                        String dbPath;
                        String[] parts = cmd.split("\\s+", 2);
                        if (parts.length == 2 && !parts[1].isBlank()) {
                            dbPath = parts[1];
                        } else {
                            java.nio.file.Path runs = java.nio.file.Paths.get(org.evochora.runtime.Config.RUNS_DIRECTORY);
                            java.nio.file.Path latest = java.nio.file.Files.list(runs)
                                    .filter(p -> p.getFileName().toString().endsWith(".sqlite"))
                                    .max(java.util.Comparator.comparingLong(p -> p.toFile().lastModified()))
                                    .orElse(null);
                            if (latest == null) { System.err.println("No .sqlite file found in runs/"); break; }
                            dbPath = latest.toString();
                        }
                        int port = 7070;
                        if (loadedConfig != null && loadedConfig.web != null && loadedConfig.web.port != null) port = loadedConfig.web.port;
                        if (web != null) web.stop();
                        web = new AnalysisWebService();
                        web.start(dbPath, port);
                        System.err.println("AnalysisWebService at http://localhost:" + port + " (DB=" + dbPath + ")");
                    } catch (Exception e) {
                        System.err.println("Failed to start view: " + e.getMessage());
                    }
                }
                case "status" -> {
                    long simTick = (sim != null) ? sim.getCurrentTick() : -1L;
                    long persistTick = (persist != null) ? persist.getLastPersistedTick() : -1L;
                    int[] orgCounts = (sim != null) ? sim.getOrganismCounts() : new int[]{0,0};
                    double tps = StatusMetricsRegistry.getTicksPerSecond();
                    String simState = (sim != null && sim.isPaused()) ? "paused" : ((sim != null && sim.isRunning()) ? "running" : "stopped");
                    String persState = (persist != null && persist.isPaused()) ? "paused" : ((persist != null && persist.isRunning()) ? "running" : "stopped");
                    String webState = (web != null) ? "running" : "stopped";
                    System.err.printf(
                            "sim: %s | persist: %s | web: %s | ticks (persist/sim): %d/%d | organisms (living/dead): %d/%d | queue: %d | tps: %.2f%n",
                            simState, persState, webState, persistTick, simTick, orgCounts[0], orgCounts[1], queue.size(), tps);
                }
                case "clear" -> {
                    // Stop running services if necessary
                    if (sim != null) { sim.shutdown(); sim = null; }
                    if (persist != null) { persist.shutdown(); persist = null; }
                    if (web != null) { web.stop(); web = null; }
                    // Reset queue to drop any pending messages
                    queue = new InMemoryTickQueue();
                    // Clear user-registered starts
                    org.evochora.server.engine.UserLoadRegistry.clearAll();
                    System.err.println("World cleared. State reset.");
                }
                case "exit" -> {
                    if (sim != null) sim.shutdown();
                    if (persist != null) persist.shutdown();
                    if (web != null) { web.stop(); web = null; }
                    System.err.println("Services stopped. Exiting.");
                    return;
                }
                default -> System.err.println("Unknown command");
            }
        }
    }
}