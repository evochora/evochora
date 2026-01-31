package org.evochora.cli.commands;

import org.evochora.cli.CommandLineInterface;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for the inspect command.
 * Tests command registration and help output.
 */
@Tag("unit")
public class InspectCommandTest {

    @Test
    void testCommandParses() {
        CommandLine cmdLine = CommandLineInterface.createCommandLine();

        assertThat(cmdLine.getSubcommands()).containsKey("inspect");
    }

    @Test
    void testHasStorageSubcommand() {
        CommandLine cmdLine = CommandLineInterface.createCommandLine();
        CommandLine inspectCmd = cmdLine.getSubcommands().get("inspect");

        assertThat(inspectCmd.getSubcommands()).containsKey("storage");
    }

    @Test
    void testHelpOutput() {
        CommandLine cmdLine = CommandLineInterface.createCommandLine();

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cmdLine.setOut(new PrintWriter(out));
        cmdLine.setErr(new PrintWriter(err));
        cmdLine.execute("inspect", "--help");

        String output = out.toString() + err.toString();
        assertThat(output).contains("inspect");
        assertThat(output).contains("storage");
    }

    @Test
    void testStorageSubcommandHelpOutput() {
        CommandLine cmdLine = CommandLineInterface.createCommandLine();

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cmdLine.setOut(new PrintWriter(out));
        cmdLine.setErr(new PrintWriter(err));
        cmdLine.execute("inspect", "storage", "--help");

        String output = out.toString() + err.toString();
        assertThat(output).contains("storage");
        assertThat(output).contains("--run-id");
    }
}
