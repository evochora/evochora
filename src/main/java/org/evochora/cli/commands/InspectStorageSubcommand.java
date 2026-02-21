package org.evochora.cli.commands;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.delta.ChunkCorruptedException;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.utils.MetadataConfigHelper;
import org.evochora.datapipeline.utils.MoleculeDataUtils;
import org.evochora.datapipeline.utils.delta.DeltaCodec;
import org.evochora.runtime.Config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.typesafe.config.ConfigFactory;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * CLI subcommand for inspecting tick data from storage.
 * <p>
 * Reads tick data chunks from FileSystem storage, decompresses to the specified tick,
 * and outputs the data in various formats.
 */
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
        names = {"-r", "--run-id"},
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
            
            // Load metadata to get totalCells for decoder
            Optional<StoragePath> metadataPath = storage.findMetadataPath(runId);
            if (metadataPath.isEmpty()) {
                spec.commandLine().getErr().println("Metadata not found for run " + runId);
                return 1;
            }
            
            SimulationMetadata metadata = storage.readMessage(metadataPath.get(), SimulationMetadata.parser());
            int totalCells = calculateTotalCells(metadata);
            
            // Create decoder
            DeltaCodec.Decoder decoder = new DeltaCodec.Decoder(totalCells);
            
            // Find batch file containing the tick
            StoragePath batchPath = findBatchContainingTick(storage, runId, tickNumber);
            
            if (batchPath == null) {
                spec.commandLine().getOut().println("No batch file found containing tick " + tickNumber + " for run " + runId);
                return 1;
            }
            
            spec.commandLine().getOut().println("Found batch file: " + batchPath.asString());
            
            // Find the chunk containing the target tick (early exit on match)
            TickDataChunk[] found = {null};
            List<String> chunkRanges = new ArrayList<>();
            storage.forEachChunkUntil(batchPath, chunk -> {
                chunkRanges.add(String.format("  Chunk: ticks %d to %d (%d ticks)",
                    chunk.getFirstTick(), chunk.getLastTick(), chunk.getTickCount()));
                if (tickNumber >= chunk.getFirstTick() && tickNumber <= chunk.getLastTick()) {
                    found[0] = chunk;
                    return false;
                }
                return true;
            });
            TickDataChunk targetChunk = found[0];

            if (targetChunk == null) {
                spec.commandLine().getOut().println("Tick " + tickNumber + " not found in any chunk in batch " + batchPath);
                printChunkRanges(chunkRanges);
                return 1;
            }
            
            // Find the delta for this tick (null if it's the snapshot)
            TickDelta targetDelta = null;
            if (tickNumber != targetChunk.getSnapshot().getTickNumber()) {
                for (TickDelta delta : targetChunk.getDeltasList()) {
                    if (delta.getTickNumber() == tickNumber) {
                        targetDelta = delta;
                        break;
                    }
                }
            }
            
            // Decompress the specific tick
            TickData targetTick;
            try {
                targetTick = decoder.decompressTick(targetChunk, tickNumber);
            } catch (ChunkCorruptedException e) {
                spec.commandLine().getErr().println("Failed to decompress tick " + tickNumber + ": " + e.getMessage());
                return 1;
            }
            
            // Output based on format
            outputTickData(targetTick, targetChunk, targetDelta, format);
            
            return 0;
            
        } catch (Exception e) {
            spec.commandLine().getErr().println("Error inspecting storage: " + e.getMessage());
            e.printStackTrace(spec.commandLine().getErr());
            return 1;
        }
    }

    private int calculateTotalCells(SimulationMetadata metadata) {
        if (metadata.getResolvedConfigJson().isEmpty()) {
            throw new IllegalStateException("Simulation metadata is missing resolved config. Cannot calculate total cells for decoder.");
        }

        int[] shape = MetadataConfigHelper.getEnvironmentShape(metadata);
        if (shape.length == 0) {
            throw new IllegalStateException("Simulation metadata has empty environment shape. Cannot calculate total cells for decoder.");
        }

        // Use long to detect overflow, then validate fits in int (DeltaCodec uses int arrays)
        long total = 1L;
        for (int dim : shape) {
            total *= dim;
        }
        if (total > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                "World too large: " + total + " cells exceeds Integer.MAX_VALUE. " +
                "Reduce environment dimensions.");
        }
        return (int) total;
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
        IBatchStorageRead storage = (IBatchStorageRead) Class.forName(className)
            .getConstructor(String.class, com.typesafe.config.Config.class)
            .newInstance(storageName, options);
        
        return storage;
    }

    private StoragePath findBatchContainingTick(IBatchStorageRead storage, String runId, long tickNumber) throws IOException {
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

    private void printChunkRanges(List<String> chunkRanges) {
        spec.commandLine().getOut().println("Available chunks in batch:");
        chunkRanges.forEach(range -> spec.commandLine().getOut().println(range));
    }

    private void outputTickData(TickData tick, TickDataChunk chunk, TickDelta delta, String format) {
        switch (format.toLowerCase()) {
            case "json":
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                spec.commandLine().getOut().println(gson.toJson(tick));
                break;
                
            case "summary":
                outputSummary(tick, chunk, delta);
                break;
                
            case "raw":
                spec.commandLine().getOut().println(tick.toString());
                break;
                
            default:
                spec.commandLine().getErr().println("Unknown format: " + format + ". Supported formats: json, summary, raw");
        }
    }

    private void outputSummary(TickData tick, TickDataChunk chunk, TickDelta delta) {
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
        out.printf("Cells: %d non-empty (%s reconstructed)%n", cellsCount, formatBytes(cellsSize));
        out.println("RNG State: " + tick.getRngState().size() + " bytes");
        out.println("Plugin States: " + tick.getPluginStatesCount());
        
        // Delta compression info
        out.println("\n=== Delta Compression Info ===");
        out.printf("Chunk: ticks %d to %d (%d ticks total)%n", 
            chunk.getFirstTick(), chunk.getLastTick(), chunk.getTickCount());
        
        if (delta == null) {
            // This tick is the snapshot
            long snapshotSize = chunk.getSnapshot().getSerializedSize();
            int snapshotCells = chunk.getSnapshot().getCellColumns().getFlatIndicesCount();
            out.println("Storage Type: SNAPSHOT (full state)");
            out.printf("Stored Size: %s (%d cells)%n", formatBytes(snapshotSize), snapshotCells);
        } else {
            // This tick is a delta
            String deltaTypeName = switch (delta.getDeltaType()) {
                case INCREMENTAL -> "INCREMENTAL (changes since last sample)";
                case ACCUMULATED -> "ACCUMULATED (changes since snapshot, checkpoint-capable)";
                default -> "UNKNOWN";
            };
            out.println("Storage Type: " + deltaTypeName);
            
            long deltaSize = delta.getSerializedSize();
            int changedCells = delta.getChangedCells().getFlatIndicesCount();
            out.printf("Stored Size: %s (%d changed cells)%n", formatBytes(deltaSize), changedCells);
            
            // Calculate compression ratio
            if (cellsSize > 0) {
                double ratio = (double) cellsSize / deltaSize;
                out.printf("Compression Ratio: %.1fx (reconstructed/stored)%n", ratio);
            }
        }
        
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
