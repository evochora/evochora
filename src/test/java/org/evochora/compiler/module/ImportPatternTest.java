package org.evochora.compiler.module;

import org.evochora.compiler.TestLexers;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.features.importdir.ImportDependencyScanHandler;
import org.evochora.compiler.frontend.DirectiveLine;
import org.evochora.compiler.frontend.lexer.IDependencyInfo;
import org.evochora.compiler.frontend.lexer.IDependencyScanContext;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests how the Lexer phase's handler of {@code .IMPORT} recognises an import line, by the module
 * file it reads for the line.
 * <p>
 * A line the handler passes over is never followed, so the module behind it is never loaded and
 * the parser never sees it, a silent failure at the very front of the pipeline. An {@code EXPORT}
 * before the directive belongs to the line, so an exported import is loaded as well. The syntax is
 * pinned here in isolation rather than only through programs that happen to use it. The handler
 * recognises a line by the directive and the quoted path alone, so a directive whose clauses are
 * malformed is still loaded and its malformation is reported by the phases that read the clauses.
 */
class ImportPatternTest {

    @BeforeAll
    static void initInstructionSet() {
        Instruction.init();
    }

    /**
     * Hands a line to the handler as the Lexer phase does, at its {@code .IMPORT} word.
     *
     * @return The paths of the module files the handler read for the line, as written.
     */
    private static List<String> loaded(String text) {
        List<Token> tokens = TestLexers.tokenizer(text, new DiagnosticsEngine(), "main.evo").scanTokens();
        int directive = 0;
        while (!(tokens.get(directive).type() == TokenType.DIRECTIVE
                && ".IMPORT".equalsIgnoreCase(tokens.get(directive).text()))) {
            directive++;
        }
        RecordingContext ctx = new RecordingContext();
        new ImportDependencyScanHandler().handle(DirectiveLine.of(tokens, directive), ctx);
        return ctx.loaded;
    }

    private static void assertRecognised(String text, String path) {
        assertThat(loaded(text)).as("'%s' is recognised as an import", text).containsExactly(path);
    }

    /** A context that resolves a path to itself and records the module files read. */
    private static final class RecordingContext implements IDependencyScanContext {
        final List<String> loaded = new ArrayList<>();

        @Override public String resolve(String path) { return path; }
        @Override public void reportError(String message) { throw new AssertionError(message); }
        @Override public void scanNestedModule(String resolvedPath, String aliasChain) { loaded.add(resolvedPath); }
        @Override public String placementChain() { return ""; }
        @Override public void scanNestedSourceFile(String resolvedPath) {}
        @Override public void addDependency(IDependencyInfo info) {}
        @Override public String sourcePath() { return "main.evo"; }
        @Override public int lineNumber() { return 1; }
        @Override public List<Token> nextLine() { return null; }
        @Override public DirectiveLine lineOf(int indexInLine) { throw new UnsupportedOperationException(); }
        @Override public CompilerOptions options() { return CompilerOptions.defaults(); }
        @Override public <T> T getOrCreate(Class<T> key, Supplier<T> factory) { return factory.get(); }
    }

    @Test
    @Tag("unit")
    void aPlainImportIsRecognised() {
        assertRecognised(".IMPORT \"modules/math.evo\" AS MATHLIB", "modules/math.evo");
    }

    @Test
    @Tag("unit")
    void anExportedImportIsRecognised() {
        assertRecognised("EXPORT .IMPORT \"modules/math.evo\" AS MATHLIB", "modules/math.evo");
    }

    @Test
    @Tag("unit")
    void usingClausesDoNotHideThePath() {
        assertRecognised(".IMPORT \"m.evo\" AS NAV USING MODULE MATHLIB AS ARITH", "m.evo");
        assertRecognised("EXPORT .IMPORT \"m.evo\" AS NAV USING MODULE MATHLIB AS ARITH MODULE B AS C", "m.evo");
    }

    @Test
    @Tag("unit")
    void theDirectiveIsRecognisedInLowerCase() {
        assertRecognised("export .import \"m.evo\" as nav", "m.evo");
    }

    @Test
    @Tag("unit")
    void aDirectiveWithoutTheAliasClauseIsStillRecognised() {
        assertRecognised(".IMPORT \"m.evo\"", "m.evo");
    }

    @Test
    @Tag("unit")
    void aDirectiveWithAMalformedUsingClauseIsStillRecognised() {
        assertRecognised(".IMPORT \"m.evo\" AS NAV USING X", "m.evo");
    }

    @Test
    @Tag("unit")
    void aWordMerelyStartingWithExportIsNotTheMarker() {
        assertThat(loaded("EXPORTED .IMPORT \"m.evo\" AS NAV")).isEmpty();
    }

    @Test
    @Tag("unit")
    void theMarkerBelongsInFrontOfTheDirective() {
        assertThat(loaded(".IMPORT EXPORT \"m.evo\" AS NAV")).isEmpty();
    }
}
