package org.evochora.cli.commands;

import com.google.gson.JsonParser;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(output).contains("--define");
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

    @Test
    void testDefineArgumentsBecomeFlagsWithAndWithoutValue() {
        Map<String, OptionalInt> defines = CompileCommand.parseDefines(List.of("A", "B=2"));

        assertThat(defines).containsExactly(
            Map.entry("A", OptionalInt.empty()),
            Map.entry("B", OptionalInt.of(2)));
    }

    @Test
    void testDefineValuesAreReadAsTheAssemblerReadsNumbers() {
        Map<String, OptionalInt> defines = CompileCommand.parseDefines(
            List.of("DEC=12", "HEX=0x1F", "BIN=0b101", "NEG=-3", "NEGHEX=-0x10", "NEGBIN=-0b11"));

        assertThat(defines).containsEntry("DEC", OptionalInt.of(12))
            .containsEntry("HEX", OptionalInt.of(31))
            .containsEntry("BIN", OptionalInt.of(5))
            .containsEntry("NEG", OptionalInt.of(-3))
            .containsEntry("NEGHEX", OptionalInt.of(-16))
            .containsEntry("NEGBIN", OptionalInt.of(-3));
        for (String form : List.of("A=+3", "A=0o17", "A=--3", "A=0x", "A=-")) {
            assertThatThrownBy(() -> CompileCommand.parseDefines(List.of(form)))
                .describedAs(form)
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not an integer");
        }
    }

    @Test
    void testDefineWithAPlusOrAnOctalValueIsAUsageError() throws Exception {
        Path sourceFile = tempDir.resolve("test.evo");
        Files.writeString(sourceFile, "NOP\n");

        for (String form : List.of("A=+3", "A=0o17")) {
            CommandLine cmdLine = CommandLineInterface.createCommandLine();
            StringWriter err = new StringWriter();
            cmdLine.setErr(new PrintWriter(err));

            int exitCode = cmdLine.execute("compile", "-f", sourceFile.toString(), "--define", form);

            assertThat(exitCode).describedAs(form).isEqualTo(2);
            assertThat(err.toString()).contains("not an integer");
        }
    }

    @Test
    void testWithoutSourceRootImportsResolveRelativeToTheMainFile() throws Exception {
        Path programDir = tempDir.resolve("program");
        Files.createDirectories(programDir.resolve("lib"));
        Files.writeString(programDir.resolve("lib/x.evo"), "NOP\n");
        Path mainFile = programDir.resolve("main.evo");
        Files.writeString(mainFile, """
            .IMPORT "lib/x.evo" AS X
            NOP
            .IFDEF EXTRA
            NOP
            .ENDDEF
            """);

        String plain = compileToJson(mainFile.toString());
        String flagged = compileToJson(mainFile.toString(), "--define", "EXTRA");

        assertThat(programIdOf(flagged)).isNotEqualTo(programIdOf(plain));
    }

    @Test
    void testDefineRejectsAValueThatIsNoIntegerANameGivenTwiceAndAMissingName() {
        assertThatThrownBy(() -> CompileCommand.parseDefines(List.of("A=two")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not an integer");
        assertThatThrownBy(() -> CompileCommand.parseDefines(List.of("A", "A=2")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("given twice");
        assertThatThrownBy(() -> CompileCommand.parseDefines(List.of("=2")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no flag name");
    }

    @Test
    void testTwoDefinesThatDifferOnlyInCaseAreAUsageError() throws Exception {
        Path sourceFile = tempDir.resolve("test.evo");
        Files.writeString(sourceFile, "NOP\n");
        CommandLine cmdLine = CommandLineInterface.createCommandLine();
        StringWriter err = new StringWriter();
        cmdLine.setErr(new PrintWriter(err));

        int exitCode = cmdLine.execute("compile", "-f", sourceFile.toString(), "--define", "a", "--define", "A=2");

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString()).contains("case-insensitive");
    }

    @Test
    void testDefineReachesTheCompilation() throws Exception {
        Path sourceFile = tempDir.resolve("flagged.evo");
        Files.writeString(sourceFile, """
            NOP
            .IFDEF B >= 2
            SETI %DR0 DATA:1
            .ENDDEF
            """);

        String plain = compileToJson(sourceFile.toString());
        String flagged = compileToJson(sourceFile.toString(), "--define", "A", "--define", "B=2");

        assertThat(programIdOf(flagged)).isNotEqualTo(programIdOf(plain));
    }

    @Test
    void testInvalidDefineIsAUsageError() throws Exception {
        Path sourceFile = tempDir.resolve("test.evo");
        Files.writeString(sourceFile, "NOP\n");
        CommandLine cmdLine = CommandLineInterface.createCommandLine();
        StringWriter err = new StringWriter();
        cmdLine.setErr(new PrintWriter(err));

        int exitCode = cmdLine.execute("compile", "-f", sourceFile.toString(), "--define", "A=x");

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString()).contains("not an integer");
    }

    private static String compileToJson(String file, String... extraArgs) {
        CommandLine cmdLine = CommandLineInterface.createCommandLine();
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cmdLine.setOut(new PrintWriter(out));
        cmdLine.setErr(new PrintWriter(err));
        List<String> args = new ArrayList<>(List.of("compile", "-f", file));
        args.addAll(List.of(extraArgs));

        int exitCode = cmdLine.execute(args.toArray(String[]::new));

        assertThat(exitCode).describedAs("stderr: %s", err).isEqualTo(0);
        return out.toString();
    }

    private static String programIdOf(String json) {
        return JsonParser.parseString(json).getAsJsonObject().get("programId").getAsString();
    }
}
