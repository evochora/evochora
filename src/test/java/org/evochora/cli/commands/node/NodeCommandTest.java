package org.evochora.cli.commands.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.evochora.cli.CommandLineInterface;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import picocli.CommandLine;

/**
 * Smoke tests for the {@code node} command group: which subcommands it carries and what its usage
 * text names.
 */
@Tag("unit")
class NodeCommandTest {

    @Test
    void nodeCarriesAllFourSubcommands() {
        CommandLine nodeCommand = CommandLineInterface.createCommandLine().getSubcommands().get("node");

        assertThat(nodeCommand.getSubcommands()).containsKeys("run", "resume", "fork", "show");
    }

    @Test
    void usageListsAllFourSubcommands() {
        CommandLine cmdLine = CommandLineInterface.createCommandLine();
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cmdLine.setOut(new PrintWriter(out));
        cmdLine.setErr(new PrintWriter(err));

        cmdLine.execute("node", "--help");

        String output = out.toString() + err.toString();
        assertThat(output).contains("run").contains("resume").contains("fork").contains("show");
    }
}
