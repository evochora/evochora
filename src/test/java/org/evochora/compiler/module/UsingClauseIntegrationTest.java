package org.evochora.compiler.module;

import org.evochora.compiler.diagnostics.Diagnostic;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.module.DependencyGraph;
import org.evochora.compiler.frontend.module.DependencyScanner;
import org.evochora.compiler.frontend.module.ModuleDescriptor;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.semantics.ModuleId;
import org.evochora.compiler.frontend.semantics.SemanticAnalyzer;
import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.compiler.frontend.semantics.SymbolTable;
import org.evochora.compiler.model.Token;
import org.evochora.compiler.model.TokenType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for USING clauses on {@code .IMPORT} directives.
 * Exercises the full pipeline: DependencyScanner → Lexer → PreProcessor → Parser → SemanticAnalyzer.
 */
class UsingClauseIntegrationTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void initInstructionSet() {
        org.evochora.runtime.isa.Instruction.init();
    }

    @Test
    @Tag("integration")
    void validUsingClauseSatisfiesRequire() throws Exception {
        // dep.evo: a simple dependency module with an exported label
        Files.writeString(tempDir.resolve("dep.evo"), "HARVEST: EXPORT\n  NOP\n");

        // lib.evo: requires DEP and uses DEP.HARVEST
        Files.writeString(tempDir.resolve("lib.evo"),
                ".REQUIRE \"dep.evo\" AS DEP\nWORK: EXPORT\n  JMPI DEP.HARVEST\n");

        // main.evo: imports dep and lib, provides dep to lib via USING
        String mainSource = ".IMPORT \"dep.evo\" AS D\n.IMPORT \"lib.evo\" AS LIB USING D AS DEP\nNOP\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        DiagnosticsEngine diagnostics = compileThroughSemantics(mainSource, mainPath);

        assertThat(diagnostics.hasErrors())
                .as("Expected no errors but got: %s", diagnostics.getDiagnostics())
                .isFalse();
    }

    @Test
    @Tag("integration")
    void missingUsingForRequireReportsError() throws Exception {
        // lib.evo: requires DEP
        Files.writeString(tempDir.resolve("lib.evo"),
                ".REQUIRE \"dep.evo\" AS DEP\nNOP\n");

        // main.evo: imports lib but does NOT provide a USING clause
        String mainSource = ".IMPORT \"lib.evo\" AS LIB\nNOP\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        DiagnosticsEngine diagnostics = compileThroughSemantics(mainSource, mainPath);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertErrorContaining(diagnostics, "DEP", "no USING clause provides it");
    }

    @Test
    @Tag("integration")
    void usingWithUnknownSourceAliasReportsError() throws Exception {
        Files.writeString(tempDir.resolve("lib.evo"),
                ".REQUIRE \"dep.evo\" AS DEP\nNOP\n");

        // main.evo: uses UNKNOWN as source alias (not a known import in main)
        String mainSource = ".IMPORT \"lib.evo\" AS LIB USING UNKNOWN AS DEP\nNOP\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        DiagnosticsEngine diagnostics = compileThroughSemantics(mainSource, mainPath);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertErrorContaining(diagnostics, "UNKNOWN", "not a known import alias");
    }

    @Test
    @Tag("integration")
    void usingWithWrongTargetAliasReportsError() throws Exception {
        Files.writeString(tempDir.resolve("dep.evo"), "NOP\n");
        Files.writeString(tempDir.resolve("lib.evo"),
                ".REQUIRE \"dep.evo\" AS DEP\nNOP\n");

        // main.evo: provides D as WRONG_NAME, but lib requires DEP
        String mainSource = ".IMPORT \"dep.evo\" AS D\n.IMPORT \"lib.evo\" AS LIB USING D AS WRONG_NAME\nNOP\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        DiagnosticsEngine diagnostics = compileThroughSemantics(mainSource, mainPath);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertErrorContaining(diagnostics, "WRONG_NAME", "does not match any .REQUIRE");
    }

    @Test
    @Tag("integration")
    void multipleUsingsAllSatisfied() throws Exception {
        Files.writeString(tempDir.resolve("dep1.evo"), "NOP\n");
        Files.writeString(tempDir.resolve("dep2.evo"), "NOP\n");
        Files.writeString(tempDir.resolve("lib.evo"),
                ".REQUIRE \"dep1.evo\" AS A\n.REQUIRE \"dep2.evo\" AS B\nNOP\n");

        String mainSource = ".IMPORT \"dep1.evo\" AS D1\n.IMPORT \"dep2.evo\" AS D2\n"
                + ".IMPORT \"lib.evo\" AS LIB USING D1 AS A USING D2 AS B\nNOP\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        DiagnosticsEngine diagnostics = compileThroughSemantics(mainSource, mainPath);

        assertThat(diagnostics.hasErrors())
                .as("Expected no errors but got: %s", diagnostics.getDiagnostics())
                .isFalse();
    }

    @Test
    @Tag("integration")
    void multipleUsingsPartiallySatisfiedReportsError() throws Exception {
        Files.writeString(tempDir.resolve("dep1.evo"), "NOP\n");
        Files.writeString(tempDir.resolve("dep2.evo"), "NOP\n");
        Files.writeString(tempDir.resolve("lib.evo"),
                ".REQUIRE \"dep1.evo\" AS A\n.REQUIRE \"dep2.evo\" AS B\nNOP\n");

        // Only provide A, not B
        String mainSource = ".IMPORT \"dep1.evo\" AS D1\n.IMPORT \"dep2.evo\" AS D2\n"
                + ".IMPORT \"lib.evo\" AS LIB USING D1 AS A\nNOP\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        DiagnosticsEngine diagnostics = compileThroughSemantics(mainSource, mainPath);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertErrorContaining(diagnostics, "B", "no USING clause provides it");
    }

    @Test
    @Tag("integration")
    void usingResolvesQualifiedSymbolCorrectly() throws Exception {
        // dep.evo: exported label HARVEST
        Files.writeString(tempDir.resolve("dep.evo"), "HARVEST: EXPORT\n  NOP\n");

        // lib.evo: requires DEP, references DEP.HARVEST
        Files.writeString(tempDir.resolve("lib.evo"),
                ".REQUIRE \"dep.evo\" AS DEP\nWORK: EXPORT\n  JMPI DEP.HARVEST\n");

        String mainSource = ".IMPORT \"dep.evo\" AS D\n.IMPORT \"lib.evo\" AS LIB USING D AS DEP\nNOP\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        SemanticsResult result = compileThroughSemanticsWithSymbols(mainSource, mainPath);

        assertThat(result.diagnostics.hasErrors())
                .as("Expected no errors but got: %s", result.diagnostics.getDiagnostics())
                .isFalse();

        // Verify that DEP.HARVEST resolves to a LABEL symbol from dep.evo
        String depPath = tempDir.resolve("dep.evo").normalize().toString().replace('\\', '/');
        SymbolTable st = result.symbolTable;
        st.setCurrentModule(new ModuleId(
                tempDir.resolve("lib.evo").normalize().toString().replace('\\', '/')));

        Token lookupToken = new Token(TokenType.IDENTIFIER, "DEP.HARVEST", null, 1, 1, depPath);
        var resolved = st.resolve(lookupToken);
        assertThat(resolved).isPresent();
        assertThat(resolved.get().type()).isEqualTo(Symbol.Type.LABEL);
        assertThat(resolved.get().name().text()).isEqualToIgnoringCase("HARVEST");
    }

    @Test
    @Tag("integration")
    void usingWithSourcedConstantsInRequiredModule() throws Exception {
        // consts.evo: shared constants
        Files.writeString(tempDir.resolve("consts.evo"),
                ".DEFINE AMOUNT DATA:5\n");

        // math.evo: standalone, exports ADD_CONST
        Files.writeString(tempDir.resolve("math.evo"),
                ".SOURCE \"consts.evo\"\n" +
                ".PROC ADD_CONST EXPORT REF X\n" +
                "  ADDI X AMOUNT\n" +
                "  RET\n" +
                ".ENDP\n");

        // user.evo: requires MATH, calls MATH.ADD_CONST
        Files.writeString(tempDir.resolve("user.evo"),
                ".REQUIRE \"math.evo\" AS MATH\n" +
                ".PROC DO_WORK EXPORT REF V\n" +
                "  CALL MATH.ADD_CONST REF V\n" +
                "  RET\n" +
                ".ENDP\n");

        String mainSource = ".IMPORT \"math.evo\" AS M\n" +
                ".IMPORT \"user.evo\" AS U USING M AS MATH\n" +
                "SETI %DR0 DATA:10\nCALL U.DO_WORK REF %DR0\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        SemanticsResult result = compileThroughSemanticsWithSymbols(mainSource, mainPath);

        assertThat(result.diagnostics.hasErrors())
                .as("Expected no errors but got: %s", result.diagnostics.getDiagnostics())
                .isFalse();
    }

    private record SemanticsResult(DiagnosticsEngine diagnostics, SymbolTable symbolTable) {}

    private DiagnosticsEngine compileThroughSemantics(String mainSource, String mainPath) {
        return compileThroughSemanticsWithSymbols(mainSource, mainPath).diagnostics;
    }

    /**
     * Runs the full compilation pipeline through semantic analysis.
     * Mirrors the phases in Compiler.java (Phase 0 through Phase 4).
     */
    private SemanticsResult compileThroughSemanticsWithSymbols(String mainSource, String mainPath) {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();

        // Phase 0: Dependency scanning
        DependencyScanner scanner = new DependencyScanner(diagnostics);
        DependencyGraph graph = scanner.scan(mainSource, mainPath, tempDir);
        if (diagnostics.hasErrors()) return new SemanticsResult(diagnostics, null);

        // Phase 1: Lex all modules
        Map<String, List<Token>> moduleTokens = new HashMap<>();
        for (ModuleDescriptor module : graph.topologicalOrder()) {
            if (module.id().path().equals(mainPath)) continue;
            String source = module.content();
            if (!source.endsWith("\n")) source += "\n";
            Lexer moduleLexer = new Lexer(source, diagnostics, module.sourcePath());
            List<Token> tokens = moduleLexer.scanTokens();
            if (!tokens.isEmpty() && tokens.getLast().type() == TokenType.END_OF_FILE) {
                tokens.removeLast();
            }
            moduleTokens.put(module.sourcePath(), tokens);
        }
        Lexer mainLexer = new Lexer(mainSource, diagnostics, mainPath);
        List<Token> mainTokens = new ArrayList<>(mainLexer.scanTokens());

        // Phase 2: Preprocessing
        PreProcessor preProcessor = new PreProcessor(mainTokens, diagnostics, tempDir,
                moduleTokens.isEmpty() ? null : moduleTokens);
        List<Token> processedTokens = preProcessor.expand();
        if (diagnostics.hasErrors()) return new SemanticsResult(diagnostics, null);

        // Phase 3: Parsing
        Parser parser = new Parser(processedTokens, diagnostics);
        List<AstNode> ast = parser.parse();
        if (diagnostics.hasErrors()) return new SemanticsResult(diagnostics, null);

        // Build file-to-module mapping
        Map<String, ModuleId> fileToModule = new HashMap<>();
        for (ModuleDescriptor module : graph.topologicalOrder()) {
            fileToModule.put(module.sourcePath(), module.id());
        }

        // Phase 4: Semantic analysis (module-aware)
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable, graph, mainPath, fileToModule);
        analyzer.analyze(ast);

        return new SemanticsResult(diagnostics, symbolTable);
    }

    private void assertErrorContaining(DiagnosticsEngine diagnostics, String... substrings) {
        List<Diagnostic> errors = diagnostics.getDiagnostics().stream()
                .filter(d -> d.type() == Diagnostic.Type.ERROR)
                .toList();
        assertThat(errors).anyMatch(error -> {
            String msg = error.message();
            for (String sub : substrings) {
                if (!msg.toLowerCase().contains(sub.toLowerCase())) return false;
            }
            return true;
        });
    }
}
