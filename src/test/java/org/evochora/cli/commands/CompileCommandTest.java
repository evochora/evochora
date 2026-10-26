package org.evochora.cli.commands;

import com.google.gson.JsonParser;
import org.evochora.cli.CommandLineInterface;
import org.evochora.compiler.api.FlagSetting;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Smoke tests for the compile command.
 * Tests basic functionality: parsing, compilation of simple programs.
 * <p>
 * Every call that reaches the compilation names its environment with {@code -e}: without it the
 * command lays the program out for the default environment of {@code reference.conf}, and a test
 * must not depend on the configuration.
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

        int exitCode = cmdLine.execute("compile", "-f", sourceFile.toString(), "-e", "50x50:torus");

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

        int exitCode = cmdLine.execute("compile", "-f", sourceFile.toString(), "-e", "50x50:bound");

        assertThat(exitCode).isEqualTo(0);
    }

    /**
     * The topology cannot be left out, and it takes the words of the simulation's configuration
     * and no other.
     */
    @Test
    void theEnvTopologyIsRequiredAndTakesOnlyTorusOrBound() throws Exception {
        Path sourceFile = tempDir.resolve("topology.asm");
        Files.writeString(sourceFile, """
            .ORG 0|0
            NOP
            """);

        assertThat(compiledAsToroidal(sourceFile, "50x50:torus")).isTrue();
        assertThat(compiledAsToroidal(sourceFile, "50x50:bound")).isFalse();

        for (String env : List.of("50x50", "50x50:toroidal")) {
            CommandLine cmdLine = CommandLineInterface.createCommandLine();
            StringWriter err = new StringWriter();
            cmdLine.setErr(new PrintWriter(err));
            assertThat(cmdLine.execute("compile", "-f", sourceFile.toString(), "-e", env)).describedAs(env).isEqualTo(2);
            assertThat(err.toString()).describedAs(env).contains("torus or bound");
        }
    }

    private static boolean compiledAsToroidal(Path sourceFile, String env) {
        CommandLine cmdLine = CommandLineInterface.createCommandLine();
        StringWriter out = new StringWriter();
        cmdLine.setOut(new PrintWriter(out));
        assertThat(cmdLine.execute("compile", "-f", sourceFile.toString(), "-e", env)).isEqualTo(0);
        return com.google.gson.JsonParser.parseString(out.toString()).getAsJsonObject()
            .getAsJsonObject("envProps").get("isToroidal").getAsBoolean();
    }

    @Test
    void testCompileNonexistentFileReturnsError() {
        CommandLine cmdLine = CommandLineInterface.createCommandLine();

        StringWriter err = new StringWriter();
        cmdLine.setErr(new PrintWriter(err));

        int exitCode = cmdLine.execute("compile", "-f", "/nonexistent/file.asm", "-e", "50x50:torus");

        assertThat(exitCode).isNotEqualTo(0);
    }

    /**
     * The command reads no configuration, so a configuration named for it would be ignored
     * without notice; it is rejected instead.
     */
    @Test
    void aConfigurationNamedForCompileIsAUsageError() {
        CommandLine cmdLine = CommandLineInterface.createCommandLine();
        StringWriter err = new StringWriter();
        cmdLine.setErr(new PrintWriter(err));

        int exitCode = cmdLine.execute("--config", "experiment.conf", "compile", "-f", "main.evo", "-e", "50x50:torus");

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString()).contains("--env");
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
        Map<String, FlagSetting> defines = CompileCommand.parseDefines(List.of("A", "B=2"));

        assertThat(defines).containsExactly(
            Map.entry("A", FlagSetting.set()),
            Map.entry("B", FlagSetting.set(2)));
    }

    @Test
    void testDefineArgumentsFalseAndTrueSwitchAFlagOffAndSetItWithoutValueInAnyCase() {
        Map<String, FlagSetting> defines = CompileCommand.parseDefines(List.of("X=false", "Y=true", "x2=FALSE", "Y2=True"));

        assertThat(defines).containsExactly(
            Map.entry("X", FlagSetting.off()),
            Map.entry("Y", FlagSetting.set()),
            Map.entry("x2", FlagSetting.off()),
            Map.entry("Y2", FlagSetting.set()));
    }

    @Test
    void testDefineValuesAreReadAsTheAssemblerReadsNumbers() {
        Map<String, FlagSetting> defines = CompileCommand.parseDefines(
            List.of("DEC=12", "HEX=0x1F", "OCT=0o17", "BIN=0b101", "NEG=-3", "NEGHEX=-0x10", "NEGBIN=-0b11"));

        assertThat(CompileCommand.parseDefines(List.of("BIG=5000000000")))
            .containsEntry("BIG", FlagSetting.set(5000000000L));
        assertThat(defines).containsEntry("DEC", FlagSetting.set(12))
            .containsEntry("HEX", FlagSetting.set(31))
            .containsEntry("OCT", FlagSetting.set(15))
            .containsEntry("BIN", FlagSetting.set(5))
            .containsEntry("NEG", FlagSetting.set(-3))
            .containsEntry("NEGHEX", FlagSetting.set(-16))
            .containsEntry("NEGBIN", FlagSetting.set(-3));
        for (String form : List.of("A=+3", "A=0o8", "A=--3", "A=0x", "A=-")) {
            assertThatThrownBy(() -> CompileCommand.parseDefines(List.of(form)))
                .describedAs(form)
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a number");
        }
    }

    @Test
    void testDefineWithAPlusOrAMalformedOctalValueIsAUsageError() throws Exception {
        Path sourceFile = tempDir.resolve("test.evo");
        Files.writeString(sourceFile, "NOP\n");

        for (String form : List.of("A=+3", "A=0o8")) {
            CommandLine cmdLine = CommandLineInterface.createCommandLine();
            StringWriter err = new StringWriter();
            cmdLine.setErr(new PrintWriter(err));

            int exitCode = cmdLine.execute("compile", "-f", sourceFile.toString(), "-e", "50x50:torus", "--define", form);

            assertThat(exitCode).describedAs(form).isEqualTo(2);
            assertThat(err.toString()).contains("not a number");
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
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("--define A: 'two' is not a number, true or false");
        assertThatThrownBy(() -> CompileCommand.parseDefines(List.of("X=maybe")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("--define X: 'maybe' is not a number, true or false");
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
    void testDefineFalseSwitchesOffAFlagTheProgramSetsByDefault() throws Exception {
        Path sourceFile = tempDir.resolve("defaulted.evo");
        Files.writeString(sourceFile, """
            .DEFINE DEFAULT X
            NOP
            .IFDEF X
            SETI %DR0 DATA:1
            .ENDDEF
            """);

        String plain = compileToJson(sourceFile.toString());
        String switchedOff = compileToJson(sourceFile.toString(), "--define", "X=false");

        assertThat(programIdOf(switchedOff)).isNotEqualTo(programIdOf(plain));
        assertThat(codeSizeOf(switchedOff)).isLessThan(codeSizeOf(plain));
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
        assertThat(err.toString()).contains("not a number");
    }

    @Test
    void testValueReachesTheCompilation() throws Exception {
        Path sourceFile = tempDir.resolve("valued.evo");
        Files.writeString(sourceFile, """
            .REQUIRE VALUE STEPS
            SETI %DR0 STEPS
            """);

        String valued = compileToJson(sourceFile.toString(), "--value", "STEPS=DATA:20");

        assertThat(JsonParser.parseString(valued).getAsJsonObject().getAsJsonObject("constantValues")
                .get("STEPS").getAsString()).isEqualTo("DATA:20");
    }

    @Test
    void testValueReachesTheCompilationWithASourceRoot() throws Exception {
        Path sourceFile = tempDir.resolve("valued.evo");
        Files.writeString(sourceFile, """
            .REQUIRE VALUE STEPS
            SETI %DR0 STEPS
            """);

        String valued = compileToJson("valued.evo", "--source-root", tempDir.toString(), "--value", "STEPS=DATA:20");

        assertThat(JsonParser.parseString(valued).getAsJsonObject().getAsJsonObject("constantValues")
                .get("STEPS").getAsString()).isEqualTo("DATA:20");
    }

    @Test
    void testValueRejectsAMissingEqualsSignAMissingNameAndAMissingText() {
        for (String form : List.of("STEPS", "=X", "STEPS=")) {
            assertThatThrownBy(() -> CompileCommand.parseValues(List.of(form)))
                .describedAs(form)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--value '" + form + "' must be NAME=TEXT");
        }
        assertThatThrownBy(() -> CompileCommand.parseValues(List.of("A=1", "A=2")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("--value A: the value is given twice");
    }

    @Test
    void testInvalidValuesAreUsageErrors() throws Exception {
        Path sourceFile = tempDir.resolve("test.evo");
        Files.writeString(sourceFile, "NOP\n");
        List<List<String>> forms = List.of(
            List.of("--value", "STEPS"),
            List.of("--value", "=X"),
            List.of("--value", "STEPS="),
            List.of("--value", "A=1", "--value", "A=2"),
            List.of("--value", "a=1", "--value", "A=2"));

        for (List<String> form : forms) {
            CommandLine cmdLine = CommandLineInterface.createCommandLine();
            StringWriter err = new StringWriter();
            cmdLine.setErr(new PrintWriter(err));
            List<String> args = new ArrayList<>(List.of("compile", "-f", sourceFile.toString()));
            args.addAll(form);

            int exitCode = cmdLine.execute(args.toArray(String[]::new));

            assertThat(exitCode).describedAs("%s", form).isEqualTo(2);
        }
    }

    private static String compileToJson(String file, String... extraArgs) {
        CommandLine cmdLine = CommandLineInterface.createCommandLine();
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cmdLine.setOut(new PrintWriter(out));
        cmdLine.setErr(new PrintWriter(err));
        List<String> args = new ArrayList<>(List.of("compile", "-f", file, "-e", "50x50:torus"));
        args.addAll(List.of(extraArgs));

        int exitCode = cmdLine.execute(args.toArray(String[]::new));

        assertThat(exitCode).describedAs("stderr: %s", err).isEqualTo(0);
        return out.toString();
    }

    private static String programIdOf(String json) {
        return JsonParser.parseString(json).getAsJsonObject().get("programId").getAsString();
    }

    private static int codeSizeOf(String json) {
        return JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("machineCodeLayout").size();
    }
}
