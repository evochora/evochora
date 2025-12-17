package org.evochora.cli.commands;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;

import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.resources.storage.FileSystemStorageResource;
import org.evochora.runtime.Config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.typesafe.config.ConfigFactory;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(
    name = "storage",
    description = "Inspect tick data from storage for debugging purposes"
)
public class InspectStorageSubcommand implements Callable<Integer> {

    @Option(
        names = {"-t", "--tick"},
        required = true,
        description = "Tick number to inspect"
    )
    private long tickNumber;

    @Option(
        names = {"-r", "--run"},
        required = true,
        description = "Simulation run ID"
    )
    private String runId;

    @Option(
        names = {"-f", "--format"},
        description = "Output format: json, summary, raw (default: summary)"
    )
    private String format = "summary";

    @Option(
        names = {"-s", "--storage"},
        description = "Storage resource name (default: tick-storage)"
    )
    private String storageName = "tick-storage";

    @ParentCommand
    private InspectCommand parent;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        com.typesafe.config.Config config = parent.getParent().getConfig();
        
        try {
            // Create storage resource
            IBatchStorageRead storage = createStorageResource(config);
            
            // Find batch file containing the tick
            StoragePath batchPath = findBatchContainingTick(storage, runId, tickNumber);
            
            if (batchPath == null) {
                spec.commandLine().getOut().println("No batch file found containing tick " + tickNumber + " for run " + runId);
                return 1;
            }
            
            spec.commandLine().getOut().println("Found batch file: " + batchPath.asString());
            
            // Read batch data
            List<TickData> ticks = storage.readBatch(batchPath);
            
            // Find the specific tick
            TickData targetTick = ticks.stream()
                .filter(tick -> tick.getTickNumber() == tickNumber)
                .findFirst()
                .orElse(null);
            
            if (targetTick == null) {
                spec.commandLine().getOut().println("Tick " + tickNumber + " not found in batch " + batchPath);
                spec.commandLine().getOut().println("Available ticks in batch: " + 
                    ticks.stream().mapToLong(TickData::getTickNumber).min().orElse(-1) + 
                    " to " + 
                    ticks.stream().mapToLong(TickData::getTickNumber).max().orElse(-1));
                return 1;
            }
            
            // Output based on format
            outputTickData(targetTick, format);
            
            return 0;
            
        } catch (Exception e) {
            spec.commandLine().getErr().println("Error inspecting storage: " + e.getMessage());
            e.printStackTrace(spec.commandLine().getErr());
            return 1;
        }
    }

    private IBatchStorageRead createStorageResource(com.typesafe.config.Config config) throws Exception {
        // Get storage configuration
        com.typesafe.config.Config pipelineConfig = config.getConfig("pipeline");
        com.typesafe.config.Config resourcesConfig = pipelineConfig.getConfig("resources");
        com.typesafe.config.Config storageConfig = resourcesConfig.getConfig(storageName);
        
        String className = storageConfig.getString("className");
        com.typesafe.config.Config options = storageConfig.hasPath("options") 
            ? storageConfig.getConfig("options") 
            : ConfigFactory.empty();
        
        // Create storage resource
        FileSystemStorageResource storage = (FileSystemStorageResource) Class.forName(className)
            .getConstructor(String.class, com.typesafe.config.Config.class)
            .newInstance(storageName, options);
        
        return storage;
    }

    private StoragePath findBatchContainingTick(IBatchStorageRead storage, String runId, long tickNumber) throws IOException {
        // List batch files for the run
        String prefix = runId + "/";
        var result = storage.listBatchFiles(prefix, null, 1000, tickNumber, tickNumber);
        
        for (StoragePath filename : result.getFilenames()) {
            // Check if this batch contains our tick
            if (filename.asString().contains("batch_")) {
                // Parse batch filename to get tick range
                String[] parts = filename.asString().split("_");
                if (parts.length >= 3) {
                    try {
                        long startTick = Long.parseLong(parts[1]);
                        long endTick = Long.parseLong(parts[2].split("\\.")[0]);
                        
                        if (tickNumber >= startTick && tickNumber <= endTick) {
                            return filename;
                        }
                    } catch (NumberFormatException e) {
                        // Skip invalid filenames
                        continue;
                    }
                }
            }
        }
        
        return null;
    }

    private void outputTickData(TickData tick, String format) {
        switch (format.toLowerCase()) {
            case "json":
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                spec.commandLine().getOut().println(gson.toJson(tick));
                break;
                
            case "summary":
                outputSummary(tick);
                break;
                
            case "raw":
                spec.commandLine().getOut().println(tick.toString());
                break;
                
            default:
                spec.commandLine().getErr().println("Unknown format: " + format + ". Supported formats: json, summary, raw");
        }
    }

    private void outputSummary(TickData tick) {
        var out = spec.commandLine().getOut();
        
        out.println("=== Tick Data Summary ===");
        out.println("Simulation Run ID: " + tick.getSimulationRunId());
        out.println("Tick Number: " + tick.getTickNumber());
        out.println("Capture Time: " + java.time.Instant.ofEpochMilli(tick.getCaptureTimeMs()));
        out.println("Organisms: " + tick.getOrganismsCount() + " alive");
        out.println("Cells: " + tick.getCellsCount() + " non-empty");
        out.println("RNG State: " + tick.getRngState().size() + " bytes");
        out.println("Strategy States: " + tick.getStrategyStatesCount());
        
        if (tick.getOrganismsCount() > 0) {
            out.println("\n=== Organism Summary ===");
            tick.getOrganismsList().forEach(org -> {
                out.printf("  ID: %d, Energy: %d, Dead: %s%n", 
                    org.getOrganismId(), 
                    org.getEnergy(), 
                    org.getIsDead());
            });
        }
        
        if (tick.getCellsCount() > 0) {
            out.println("\n=== Cell Summary ===");
            tick.getCellsList().stream()
                .limit(10) // Show first 10 cells
                .forEach(cell -> {
                    int moleculeInt = cell.getMoleculeData();
                    int type = moleculeInt & Config.TYPE_MASK;
                    int value = extractSignedValue(moleculeInt);
                    out.printf("  Index: %d, Type: %d, Value: %d, Owner: %d%n",
                        cell.getFlatIndex(),
                        type,
                        value,
                        cell.getOwnerId());
                });
            if (tick.getCellsCount() > 10) {
                out.println("  ... and " + (tick.getCellsCount() - 10) + " more cells");
            }
        }
    }

    private static int extractSignedValue(int moleculeInt) {
        int rawValue = moleculeInt & org.evochora.runtime.Config.VALUE_MASK;
        if ((rawValue & (1 << (org.evochora.runtime.Config.VALUE_BITS - 1))) != 0) {
            rawValue |= ~((1 << org.evochora.runtime.Config.VALUE_BITS) - 1);
        }
        return rawValue;
    }
}

