package org.evochora.cli.commands;

import org.evochora.cli.CommandLineInterface;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for the cleanup command.
 * Tests command parsing and validation.
 */
@Tag("unit")
public class CleanupCommandTest {

    @Test
    void testCommandParses() {
        CommandLine cmdLine = CommandLineInterface.createCommandLine();

        assertThat(cmdLine.getSubcommands()).containsKey("cleanup");
    }

    @Test
    void testHelpOutput() {
        CommandLine cmdLine = CommandLineInterface.createCommandLine();

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cmdLine.setOut(new PrintWriter(out));
        cmdLine.setErr(new PrintWriter(err));
        cmdLine.execute("cleanup", "--help");

        String output = out.toString() + err.toString();
        assertThat(output).contains("cleanup");
        assertThat(output).contains("--keep");
        assertThat(output).contains("--delete");
        assertThat(output).contains("--force");
        assertThat(output).contains("--compact");
    }

    @Test
    void testRequiresPatternOrCompact() {
        CommandLine cmdLine = CommandLineInterface.createCommandLine();

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cmdLine.setOut(new PrintWriter(out));
        cmdLine.setErr(new PrintWriter(err));

        // Run without any pattern - should fail
        int exitCode = cmdLine.execute("cleanup");

        assertThat(exitCode).isNotEqualTo(0);
        assertThat(err.toString()).contains("--keep").contains("--delete").contains("--compact");
    }

    @Test
    void testMutuallyExclusivePatterns() {
        CommandLine cmdLine = CommandLineInterface.createCommandLine();

        StringWriter err = new StringWriter();
        cmdLine.setErr(new PrintWriter(err));

        // Both --keep and --delete should fail
        int exitCode = cmdLine.execute("cleanup", "--keep", "run-*", "--delete", "run-*");

        assertThat(exitCode).isNotEqualTo(0);
    }
}
