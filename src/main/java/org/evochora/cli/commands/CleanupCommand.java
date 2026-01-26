package org.evochora.cli.commands;

import java.util.concurrent.Callable;

import org.evochora.cli.CommandLineInterface;
import org.evochora.cli.cleanup.CleanupService;
import org.evochora.cli.cleanup.CleanupService.CleanupResult;
import org.evochora.cli.cleanup.CompactionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * CLI command to clean up simulation runs from storage, database, and topics.
 * <p>
 * Supports glob patterns to specify which runs to keep or delete.
 * Default mode is dry-run (preview only), use --force to execute deletion.
 */
@Command(
    name = "cleanup",
    description = "Clean up simulation runs from storage, database, and topics"
)
public class CleanupCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(CleanupCommand.class);

    /**
     * Mutually exclusive pattern options: either --keep or --delete, but not both.
     */
    static class PatternOptions {
        @Option(
            names = {"--keep"},
            description = "Glob pattern for runs to KEEP (delete all others)"
        )
        String keepPattern;

        @Option(
            names = {"--delete"},
            description = "Glob pattern for runs to DELETE (keep all others)"
        )
        String deletePattern;
    }

    @ArgGroup(exclusive = true, multiplicity = "0..1")
    PatternOptions patternOptions;

    @Option(
        names = {"--storage"},
        description = "Target storage directories"
    )
    private boolean targetStorage;

    @Option(
        names = {"--database"},
        description = "Target database schemas"
    )
    private boolean targetDatabase;

    @Option(
        names = {"--topics"},
        description = "Target Artemis topics/queues"
    )
    private boolean targetTopics;

    @Option(
        names = {"--force"},
        description = "Execute deletion (default: dry-run preview only)"
    )
    private boolean force;

    @Option(
        names = {"--compact"},
        description = "Compact H2 database to reclaim disk space (only works when no other connections are active)"
    )
    private boolean compact;

    @ParentCommand
    private CommandLineInterface parent;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();

        try {
            var config = parent.getConfig();
            var service = new CleanupService(config);

            // Handle --compact only mode (no pattern specified)
            if (patternOptions == null) {
                if (!compact) {
                    err.println("Error: Either --keep or --delete pattern is required, or use --compact alone.");
                    return 1;
                }
                return executeCompactOnly(service, out, err);
            }

            // Determine targets (default: all if none specified)
            boolean allTargets = !targetStorage && !targetDatabase && !targetTopics;
            boolean doStorage = allTargets || targetStorage;
            boolean doDatabase = allTargets || targetDatabase;
            boolean doTopics = allTargets || targetTopics;

            // Determine pattern mode
            boolean keepMode = patternOptions.keepPattern != null;
            String pattern = keepMode ? patternOptions.keepPattern : patternOptions.deletePattern;

            // Header
            if (force) {
                out.println("\n=== Cleanup Execution ===");
            } else {
                out.println("\n=== Cleanup Preview (dry-run mode, use --force to execute) ===");
            }
            out.printf("Pattern: %s runs matching \"%s\"%n%n", keepMode ? "KEEP" : "DELETE", pattern);

            CleanupResult result = service.cleanup(
                pattern,
                keepMode,
                doStorage,
                doDatabase,
                doTopics,
                force,
                out
            );

            // Summary
            out.println("=== Summary ===");

            int totalToDelete = 0;

            if (result.storage() != null) {
                out.printf("Storage:  %d kept, %d %s%n",
                    result.storage().kept(),
                    result.storage().deleted(),
                    force ? "deleted" : "to delete");
                totalToDelete += result.storage().deleted();
            }
            if (result.database() != null) {
                out.printf("Database: %d kept, %d %s%n",
                    result.database().kept(),
                    result.database().deleted(),
                    force ? "deleted" : "to delete");
                totalToDelete += result.database().deleted();
            }
            if (result.topics() != null) {
                out.printf("Topics:   %d kept, %d %s%n",
                    result.topics().kept(),
                    result.topics().deleted(),
                    force ? "deleted" : "to delete");
                totalToDelete += result.topics().deleted();
            }

            if (!force && totalToDelete > 0) {
                out.println("\nRun with --force to execute deletion.");
            }

            // Attempt database compaction after forced cleanup
            if (force && doDatabase && result.database() != null && result.database().deleted() > 0) {
                out.println();
                attemptCompaction(service, out, compact);
            } else if (compact && doDatabase) {
                // --compact explicitly requested
                out.println();
                attemptCompaction(service, out, true);
            }

            return 0;

        } catch (Exception e) {
            log.error("Cleanup failed: {}", e.getMessage());
            err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Executes compaction only (no cleanup), used when --compact is specified without a pattern.
     */
    private Integer executeCompactOnly(CleanupService service, java.io.PrintWriter out, java.io.PrintWriter err) {
        out.println("\n=== Database Compaction ===");
        CompactionResult result = service.compactDatabase();

        if (result.success()) {
            out.println(result.message());
            return 0;
        } else {
            err.println("Warning: " + result.message());
            return 1;
        }
    }

    /**
     * Attempts database compaction and prints appropriate messages.
     *
     * @param service the cleanup service
     * @param out output writer
     * @param explicit whether compaction was explicitly requested via --compact
     */
    private void attemptCompaction(CleanupService service, java.io.PrintWriter out, boolean explicit) {
        out.println("Compacting database...");
        CompactionResult result = service.compactDatabase();

        if (result.success()) {
            out.println(result.message());
        } else {
            if (explicit) {
                out.println("Warning: " + result.message());
            } else {
                out.println("Warning: " + result.message());
                out.println("Run 'cleanup --compact' later to reclaim disk space.");
            }
        }
    }
}
