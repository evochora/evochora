package org.evochora.cli.commands;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;

import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.resources.storage.FileSystemStorageResource;
import org.evochora.datapipeline.utils.MoleculeDataUtils;
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
            // Use 0 as startTick to ensure we find batches starting before the target tick
            StoragePath batchPath = findBatchContainingTick(storage, runId, tickNumber, 0);
            
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

    private StoragePath findBatchContainingTick(IBatchStorageRead storage, String runId, long tickNumber, long searchStartTick) throws IOException {
        // Search in the raw directory specifically to narrow down the search
        String prefix = runId + "/raw/";
        String continuationToken = null;
        
        do {
            var result = storage.listBatchFiles(prefix, continuationToken, 10000);
            
            for (StoragePath path : result.getFilenames()) {
                String fullPath = path.asString();
                String filename = new java.io.File(fullPath).getName();
                
                if (filename.startsWith("batch_")) {
                    String[] parts = filename.split("_");
                    if (parts.length >= 3) {
                        try {
                            long startTick = Long.parseLong(parts[1]);
                            
                            String endTickPart = parts[2];
                            int firstDot = endTickPart.indexOf('.');
                            if (firstDot > 0) {
                                endTickPart = endTickPart.substring(0, firstDot);
                            }
                            
                            long endTick = Long.parseLong(endTickPart);
                            
                            if (tickNumber >= startTick && tickNumber <= endTick) {
                                return path;
                            }
                        } catch (NumberFormatException e) {
                            continue;
                        }
                    }
                }
            }
            
            continuationToken = result.getNextContinuationToken();
            
        } while (continuationToken != null);
        
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
        
        // Calculate sizes
        long organismsSize = tick.getOrganismsList().stream().mapToLong(org.evochora.datapipeline.api.contracts.OrganismState::getSerializedSize).sum();
        long cellsSize = tick.getCellColumns().getSerializedSize();
        int cellsCount = tick.getCellColumns().getFlatIndicesCount();
        
        out.println("=== Tick Data Summary ===");
        out.println("Simulation Run ID: " + tick.getSimulationRunId());
        out.println("Tick Number: " + tick.getTickNumber());
        out.println("Capture Time: " + java.time.Instant.ofEpochMilli(tick.getCaptureTimeMs()));
        out.printf("Organisms: %d alive (%s)%n", tick.getOrganismsCount(), formatBytes(organismsSize));
        out.printf("Cells: %d non-empty (%s)%n", cellsCount, formatBytes(cellsSize));
        out.println("RNG State: " + tick.getRngState().size() + " bytes");
        out.println("Strategy States: " + tick.getStrategyStatesCount());
        
        if (tick.getOrganismsCount() > 0) {
            out.println("\n=== Organism Summary ===");
            tick.getOrganismsList().stream()
                .limit(10)
                .forEach(org -> {
                    out.printf("  ID: %d, Energy: %d, Entropy: %d%n", 
                        org.getOrganismId(), 
                        org.getEnergy(),
                        org.getEntropyRegister());
                });
            if (tick.getOrganismsCount() > 10) {
                out.println("  ... and " + (tick.getOrganismsCount() - 10) + " more organisms");
            }
        }
        
        if (cellsCount > 0) {
            out.println("\n=== Cell Summary ===");
            CellDataColumns columns = tick.getCellColumns();
            int limit = Math.min(10, cellsCount);
            
            for (int i = 0; i < limit; i++) {
                int flatIndex = columns.getFlatIndices(i);
                int moleculeInt = columns.getMoleculeData(i);
                int ownerId = columns.getOwnerIds(i);
                
                int type = moleculeInt & Config.TYPE_MASK;
                int value = MoleculeDataUtils.extractSignedValue(moleculeInt);
                
                out.printf("  Index: %d, Type: %d, Value: %d, Owner: %d%n",
                    flatIndex,
                    type,
                    value,
                    ownerId);
            }
            if (cellsCount > 10) {
                out.println("  ... and " + (cellsCount - 10) + " more cells");
            }
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp-1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
}

