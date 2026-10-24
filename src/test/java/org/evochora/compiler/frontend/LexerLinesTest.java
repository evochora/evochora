package org.evochora.compiler.frontend;

import org.evochora.compiler.FeatureRegistry;
import org.evochora.compiler.StandardFeatures;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.FlagSetting;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.DependencyGraph;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;
import org.evochora.compiler.util.SourceRootResolver;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.evochora.compiler.TestDiagnostics.located;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Lexer phase reads a file as the tokenizer reads it, line by statement line: a {@code ;}
 * ends a statement, a {@code #} inside a string is part of the string, and a string that spans
 * lines is one token. The scan of the directives follows these lines, so a directive after a
 * {@code ;}, a path with a {@code #} and a path over two lines are found; the preprocessor
 * decides afterwards whether the line is acceptable. A file is tokenized once, however often it
 * is imported, and the flags set before an import hold inside the imported file.
 */
@Tag("integration")
class LexerLinesTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void initInstructionSet() {
        Instruction.init();
    }

    @Test
    void aStatementAfterAnImportOnItsLineLeavesTheImportFound() throws Exception {
        Files.writeString(tempDir.resolve("lib.evo"), "NOP\n");

        Reading reading = read(".IMPORT \"lib.evo\" AS LIB; NOP\n", CompilerOptions.defaults());

        assertThat(reading.graph.tokens()).containsKey(resolved("lib.evo"));
        assertThat(reading.graph.placements()).extracting(p -> p.aliasChain()).containsExactly("LIB", "");
    }

    /**
     * An import, a flag directive and a flag removal share their line with other statements as
     * the preprocessor lets them; only a head of a conditional block has to stand alone, which
     * the scan judges on the physical line as the preprocessor does.
     */
    @Test
    void aStatementNextToADirectiveOnItsLineIsJudgedAsThePreprocessorJudgesIt() throws Exception {
        Files.writeString(tempDir.resolve("lib.evo"), "NOP\n");
        Files.writeString(tempDir.resolve("big.evo"), "NOP\n");

        Reading importBehind = read("NOP; .IMPORT \"lib.evo\" AS LIB\n", CompilerOptions.defaults());
        assertThat(importBehind.graph.tokens()).containsKey(resolved("lib.evo"));

        Reading defineWithNeighbour = read(".DEFINE BIG ; NOP\n.IFDEF BIG\n.SOURCE \"big.evo\"\n.ENDDEF\n",
                CompilerOptions.defaults());
        assertThat(defineWithNeighbour.graph.tokens()).containsKey(resolved("big.evo"));

        Reading undefBehind = read(".DEFINE BIG\nNOP ; .UNDEF BIG\n.IFDEF BIG\n.SOURCE \"big.evo\"\n.ENDDEF\n",
                CompilerOptions.defaults());
        assertThat(undefBehind.graph.tokens()).doesNotContainKey(resolved("big.evo"));

        Reading headBehind = read(".DEFINE BIG\nNOP ; .IFDEF BIG\n.SOURCE \"big.evo\"\n.ENDDEF\n",
                CompilerOptions.defaults());
        assertThat(headBehind.graph.tokens()).doesNotContainKey(resolved("big.evo"));
    }

    /**
     * A statement after a head or a divider on its line breaks the line rule too; the
     * preprocessor removes the whole block, so the scan reads none of its branches.
     */
    @Test
    void aStatementAfterAHeadOrADividerOnItsLineSkipsTheBlock() throws Exception {
        Files.writeString(tempDir.resolve("big.evo"), "NOP\n");

        for (String source : new String[]{
                ".DEFINE BIG\n.IFDEF BIG; NOP\n.SOURCE \"big.evo\"\n.ENDDEF\n",
                ".DEFINE BIG\n.IFDEF NO\n.ELSEIFDEF BIG; NOP\n.SOURCE \"big.evo\"\n.ENDDEF\n",
                ".IFDEF NO\n.ELSEDEF; NOP\n.SOURCE \"big.evo\"\n.ENDDEF\n"}) {
            Reading reading = read(source, CompilerOptions.defaults());
            assertThat(reading.diagnostics.hasErrors()).as(source).isFalse();
            assertThat(reading.graph.tokens()).as(source).doesNotContainKey(resolved("big.evo"));
        }
    }

    @Test
    void aHashInsideAPathIsPartOfThePath() throws Exception {
        Files.writeString(tempDir.resolve("lib#1.evo"), "NOP\n");

        Reading reading = read(".IMPORT \"lib#1.evo\" AS LIB\n", CompilerOptions.defaults());

        assertThat(reading.diagnostics.hasErrors()).isFalse();
        assertThat(reading.graph.tokens()).containsKey(resolved("lib#1.evo"));
    }

    /**
     * A string over two lines is one token, so the path is the whole string; no file of that
     * name is made, because not every file system allows a line break in a name. The one message
     * names the whole path: the file is not found, or, where the name is not allowed, the path
     * cannot be resolved.
     */
    @Test
    void aPathOverTwoLinesIsReadWhole() {
        Reading reading = read("# A comment line.\n.SOURCE \"lib\n.evo\"\n", CompilerOptions.defaults());

        assertThat(reading.diagnostics.getDiagnostics()).hasSize(1);
        assertThat(located(reading.diagnostics.getDiagnostics().get(0)))
                .startsWith(resolved("main.evo") + ":2: ").contains("lib\n.evo");
    }

    @Test
    void aFlagDefinedBeforeAnImportHoldsInsideTheImportedFile() throws Exception {
        Files.writeString(tempDir.resolve("lib.evo"), ".IFDEF BIG\n.SOURCE \"big.evo\"\n.ENDDEF\nNOP\n");
        Files.writeString(tempDir.resolve("big.evo"), "NOP\n");

        Reading with = read(".DEFINE BIG\n.IMPORT \"lib.evo\" AS LIB\n", CompilerOptions.defaults());
        Reading without = read(".IMPORT \"lib.evo\" AS LIB\n", CompilerOptions.defaults());

        assertThat(with.graph.tokens()).containsKey(resolved("big.evo"));
        assertThat(without.graph.tokens()).doesNotContainKey(resolved("big.evo"));
    }

    @Test
    void aComputedFlagDefinedBeforeAnImportHoldsInsideTheImportedFile() throws Exception {
        Files.writeString(tempDir.resolve("lib.evo"), ".IFDEF HALF = 2\n.SOURCE \"big.evo\"\n.ENDDEF\nNOP\n");
        Files.writeString(tempDir.resolve("big.evo"), "NOP\n");

        Reading with = read(".DEFINE ROWS 4\n.DEFINE HALF (ROWS/2)\n.IMPORT \"lib.evo\" AS LIB\n",
                CompilerOptions.defaults());
        Reading without = read(".DEFINE ROWS 6\n.DEFINE HALF (ROWS/2)\n.IMPORT \"lib.evo\" AS LIB\n",
                CompilerOptions.defaults());

        assertThat(with.diagnostics.hasErrors()).as(with.diagnostics.summary()).isFalse();
        assertThat(with.graph.tokens()).containsKey(resolved("big.evo"));
        assertThat(without.graph.tokens()).doesNotContainKey(resolved("big.evo"));
    }

    @Test
    void aFileImportedTwiceIsTokenizedOnceAndItsErrorReportedOnce() throws Exception {
        Files.writeString(tempDir.resolve("lib.evo"), "# A comment line.\nNOP §\n");

        Reading reading = read(".IMPORT \"lib.evo\" AS A\n.IMPORT \"lib.evo\" AS B\n", CompilerOptions.defaults());

        assertThat(reading.diagnostics.getDiagnostics()).hasSize(1);
        assertThat(located(reading.diagnostics.getDiagnostics().get(0)))
                .isEqualTo(resolved("lib.evo") + ":2: Unexpected character: §");
    }

    @Test
    void aConfiguredFlagIsSeenByTheFirstLineRead() throws Exception {
        Files.writeString(tempDir.resolve("big.evo"), "NOP\n");
        CompilerOptions options = new CompilerOptions(List.of(new SourceRoot(".", null)),
                Map.of("BIG", FlagSetting.set()), Map.of());

        Reading reading = read(".IFDEF BIG\n.SOURCE \"big.evo\"\n.ENDDEF\n", options);

        assertThat(reading.graph.tokens()).containsKey(resolved("big.evo"));
    }

    @Test
    void aSourceCycleIsReportedAtTheLineThatClosesIt() throws Exception {
        Files.writeString(tempDir.resolve("a.evo"), ".SOURCE \"b.evo\"\nNOP\n");
        Files.writeString(tempDir.resolve("b.evo"), "NOP\n.SOURCE \"a.evo\"\n");

        Reading reading = read(".SOURCE \"a.evo\"\n", CompilerOptions.defaults());

        assertThat(reading.diagnostics.getDiagnostics()).hasSize(1);
        assertThat(located(reading.diagnostics.getDiagnostics().get(0)))
                .isEqualTo(resolved("b.evo") + ":2: Circular .SOURCE detected: a.evo");
    }

    @Test
    void aFileThatSourcesItselfIsReported() throws Exception {
        Files.writeString(tempDir.resolve("self.evo"), "# A comment line.\n.SOURCE \"self.evo\"\n");

        Reading reading = read(".SOURCE \"self.evo\"\n", CompilerOptions.defaults());

        assertThat(reading.diagnostics.getDiagnostics()).hasSize(1);
        assertThat(located(reading.diagnostics.getDiagnostics().get(0)))
                .isEqualTo(resolved("self.evo") + ":2: Circular .SOURCE detected: self.evo");
    }

    private record Reading(DependencyGraph graph, DiagnosticsEngine diagnostics) {
    }

    private Reading read(String mainSource, CompilerOptions options) {
        FeatureRegistry features = new FeatureRegistry(new RuntimeInstructionSetAdapter());
        StandardFeatures.all().forEach(f -> f.register(features));
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(diagnostics, new SourceRootResolver(List.of(new SourceRoot(".", null)), tempDir),
                features.dependencyScanHandlers(), options, new RuntimeInstructionSetAdapter(), features.lexerSymbols(),
                features.operations(), Flags.of(options.defines(), features.operations().reservedNames()));
        DependencyGraph graph = lexer.read(mainSource, resolved("main.evo"), "");
        return new Reading(graph, diagnostics);
    }

    /**
     * The path of a file in the temporary directory as the resolver returns it: absolute, with
     * forward slashes on every platform.
     */
    private String resolved(String fileName) {
        return tempDir.resolve(fileName).toString().replace('\\', '/');
    }
}
