package org.evochora.cli.commands;

import org.evochora.cli.CommandLineInterface;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for the compile command.
 * Tests basic functionality: parsing, compilation of simple programs.
 */
@Tag("unit")
public class CompileCommandTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void initInstructions() {
        Instruction.init();
    }

    @Test
    void testCommandParses() {
        CommandLine cmdLine = CommandLineInterface.createCommandLine();

        assertThat(cmdLine.getSubcommands()).containsKey("compile");
    }

    @Test
    void testHelpOutput() {
        CommandLine cmdLine = CommandLineInterface.createCommandLine();

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cmdLine.setOut(new PrintWriter(out));
        cmdLine.setErr(new PrintWriter(err));
        cmdLine.execute("compile", "--help");

        String output = out.toString() + err.toString();
        assertThat(output).contains("compile");
        assertThat(output).contains("--file");
        assertThat(output).contains("--env");
    }

    @Test
    void testCompileSimpleProgram() throws Exception {
        // Create a simple assembly source file
        Path sourceFile = tempDir.resolve("test.asm");
        Files.writeString(sourceFile, """
            .ORG 0|0
            NOP
            """);

        CommandLine cmdLine = CommandLineInterface.createCommandLine();

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cmdLine.setOut(new PrintWriter(out));
        cmdLine.setErr(new PrintWriter(err));

        int exitCode = cmdLine.execute("compile", "-f", sourceFile.toString());

        assertThat(exitCode)
            .describedAs("Exit code should be 0. stderr: %s, stdout: %s", err.toString(), out.toString())
            .isEqualTo(0);
        assertThat(out.toString()).contains("programId"); // JSON output contains programId
    }

    @Test
    void testCompileWithEnvOption() throws Exception {
        Path sourceFile = tempDir.resolve("test.asm");
        Files.writeString(sourceFile, """
            .ORG 0|0
            NOP
            """);

        CommandLine cmdLine = CommandLineInterface.createCommandLine();

        StringWriter out = new StringWriter();
        cmdLine.setOut(new PrintWriter(out));

        int exitCode = cmdLine.execute("compile", "-f", sourceFile.toString(), "-e", "50x50:bounded");

        assertThat(exitCode).isEqualTo(0);
    }

    @Test
    void testCompileNonexistentFileReturnsError() {
        CommandLine cmdLine = CommandLineInterface.createCommandLine();

        StringWriter err = new StringWriter();
        cmdLine.setErr(new PrintWriter(err));

        int exitCode = cmdLine.execute("compile", "-f", "/nonexistent/file.asm");

        assertThat(exitCode).isNotEqualTo(0);
    }

    @Test
    void testMissingRequiredFileOption() {
        CommandLine cmdLine = CommandLineInterface.createCommandLine();

        StringWriter err = new StringWriter();
        cmdLine.setErr(new PrintWriter(err));

        int exitCode = cmdLine.execute("compile");

        assertThat(exitCode).isNotEqualTo(0);
        assertThat(err.toString()).contains("--file");
    }
}
