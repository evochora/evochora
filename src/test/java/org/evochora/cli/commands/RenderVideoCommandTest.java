package org.evochora.cli.commands;

import org.evochora.cli.CommandLineInterface;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for the video rendering command.
 * Tests command registration, subcommand discovery, and option parsing.
 */
@Tag("unit")
public class RenderVideoCommandTest {

    @Test
    void testVideoCommandRegistered() {
        CommandLine cmdLine = CommandLineInterface.createCommandLine();

        assertThat(cmdLine.getSubcommands()).containsKey("video");
    }

    @Test
    void testHelpOutput() {
        CommandLine cmdLine = CommandLineInterface.createCommandLine();

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cmdLine.setOut(new PrintWriter(out));
        cmdLine.setErr(new PrintWriter(err));
        cmdLine.execute("video", "--help");

        // Help may go to stdout or stderr depending on PicoCLI version
        // Video command now shows subcommands, not detailed options
        String output = out.toString() + err.toString();
        assertThat(output).contains("video");
        assertThat(output).contains("exact");
        assertThat(output).contains("minimap");
    }

    @Test
    void testExactSubcommandRegistered() {
        CommandLine cmdLine = CommandLineInterface.createCommandLine();

        CommandLine videoCmd = cmdLine.getSubcommands().get("video");
        assertThat(videoCmd).isNotNull();
        assertThat(videoCmd.getSubcommands()).containsKey("exact");
    }

    @Test
    void testMinimapSubcommandRegistered() {
        CommandLine cmdLine = CommandLineInterface.createCommandLine();

        CommandLine videoCmd = cmdLine.getSubcommands().get("video");
        assertThat(videoCmd).isNotNull();
        assertThat(videoCmd.getSubcommands()).containsKey("minimap");
    }

    @Test
    void testExactSubcommandHelp() {
        CommandLine cmdLine = CommandLineInterface.createCommandLine();

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cmdLine.setOut(new PrintWriter(out));
        cmdLine.setErr(new PrintWriter(err));
        cmdLine.execute("video", "exact", "--help");

        String output = out.toString() + err.toString();
        assertThat(output).contains("exact");
        assertThat(output).contains("--scale");
    }

    @Test
    void testMinimapSubcommandHelp() {
        CommandLine cmdLine = CommandLineInterface.createCommandLine();

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cmdLine.setOut(new PrintWriter(out));
        cmdLine.setErr(new PrintWriter(err));
        cmdLine.execute("video", "minimap", "--help");

        String output = out.toString() + err.toString();
        assertThat(output).contains("minimap");
        assertThat(output).contains("--scale");
    }

    @Test
    void testVideoWithoutSubcommandShowsError() {
        CommandLine cmdLine = CommandLineInterface.createCommandLine();

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cmdLine.setOut(new PrintWriter(out));
        cmdLine.setErr(new PrintWriter(err));

        // Running video without a renderer subcommand - should fail gracefully
        // (The actual command needs storage, so it will fail, but should mention renderer)
        int exitCode = cmdLine.execute("video");

        // Should not succeed without a renderer specified
        assertThat(exitCode).isNotEqualTo(0);
    }
}
