package org.evochora.compiler.frontend;

import org.evochora.compiler.FeatureRegistry;
import org.evochora.compiler.StandardFeatures;
import org.evochora.compiler.api.CompilerOptions;
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
import java.util.OptionalLong;

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
    void aDirectiveAfterAStatementEndIsFound() throws Exception {
        Files.writeString(tempDir.resolve("lib.evo"), "NOP\n");

        Reading reading = read("NOP; .IMPORT \"lib.evo\" AS LIB\n", CompilerOptions.defaults());

        assertThat(reading.graph.tokens()).containsKey(resolved("lib.evo"));
        assertThat(reading.graph.placements()).extracting(p -> p.aliasChain()).containsExactly("LIB", "");
    }

    @Test
    void aHashInsideAPathIsPartOfThePath() throws Exception {
        Files.writeString(tempDir.resolve("lib#1.evo"), "NOP\n");

        Reading reading = read(".IMPORT \"lib#1.evo\" AS LIB\n", CompilerOptions.defaults());

        assertThat(reading.diagnostics.hasErrors()).isFalse();
        assertThat(reading.graph.tokens()).containsKey(resolved("lib#1.evo"));
    }

    @Test
    void aPathOverTwoLinesIsReadWhole() throws Exception {
        Files.writeString(tempDir.resolve("lib\n.evo"), "NOP\n");

        Reading reading = read(".IMPORT \"lib\n.evo\" AS LIB\n", CompilerOptions.defaults());

        assertThat(reading.diagnostics.hasErrors()).isFalse();
        assertThat(reading.graph.tokens()).containsKey(resolved("lib\n.evo"));
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
    void aFileImportedTwiceIsTokenizedOnceAndItsErrorReportedOnce() throws Exception {
        Files.writeString(tempDir.resolve("lib.evo"), "NOP §\n");

        Reading reading = read(".IMPORT \"lib.evo\" AS A\n.IMPORT \"lib.evo\" AS B\n", CompilerOptions.defaults());

        assertThat(reading.diagnostics.getDiagnostics()).hasSize(1);
        assertThat(reading.diagnostics.getDiagnostics().get(0).message()).contains("Unexpected character").contains("§");
    }

    @Test
    void aConfiguredFlagIsSeenByTheFirstLineRead() throws Exception {
        Files.writeString(tempDir.resolve("big.evo"), "NOP\n");
        CompilerOptions options = new CompilerOptions(List.of(new SourceRoot(".", null)),
                Map.of("BIG", OptionalLong.empty()), Map.of());

        Reading reading = read(".IFDEF BIG\n.SOURCE \"big.evo\"\n.ENDDEF\n", options);

        assertThat(reading.graph.tokens()).containsKey(resolved("big.evo"));
    }

    private record Reading(DependencyGraph graph, DiagnosticsEngine diagnostics) {
    }

    private Reading read(String mainSource, CompilerOptions options) {
        FeatureRegistry features = new FeatureRegistry(new RuntimeInstructionSetAdapter());
        StandardFeatures.all().forEach(f -> f.register(features));
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(diagnostics, new SourceRootResolver(List.of(new SourceRoot(".", null)), tempDir),
                features.dependencyScanHandlers(), options, new RuntimeInstructionSetAdapter(), features.lexerSymbols());
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
