package org.evochora.compiler.module;

import org.evochora.compiler.api.SourceRoot;
import org.evochora.compiler.diagnostics.Diagnostic;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.module.DependencyGraph;
import org.evochora.compiler.frontend.module.DependencyScanner;
import org.evochora.compiler.frontend.module.ModuleDescriptor;
import org.evochora.compiler.util.SourceRootResolver;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ParserStatementRegistry;
import org.evochora.compiler.features.ctx.PopCtxDirectiveHandler;
import org.evochora.compiler.features.ctx.PushCtxDirectiveHandler;
import org.evochora.compiler.features.define.DefineDirectiveHandler;
import org.evochora.compiler.features.dir.DirDirectiveHandler;
import org.evochora.compiler.features.importdir.ImportDirectiveHandler;
import org.evochora.compiler.features.org.OrgDirectiveHandler;
import org.evochora.compiler.features.place.PlaceDirectiveHandler;
import org.evochora.compiler.features.proc.PregDirectiveHandler;
import org.evochora.compiler.features.proc.ProcDirectiveHandler;
import org.evochora.compiler.features.reg.RegDirectiveHandler;
import org.evochora.compiler.features.require.RequireDirectiveHandler;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.features.ctx.PopCtxPreProcessorHandler;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;
import org.evochora.compiler.frontend.preprocessor.PreProcessorHandlerRegistry;
import org.evochora.compiler.features.importdir.ImportSourceHandler;
import org.evochora.compiler.features.macro.MacroDirectiveHandler;
import org.evochora.compiler.features.source.SourceDirectiveHandler;

import org.evochora.compiler.TestRegistries;
import org.evochora.compiler.frontend.semantics.SemanticAnalyzer;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
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
 * Exercises the full pipeline: DependencyScanner -> Lexer -> PreProcessor -> Parser -> SemanticAnalyzer.
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
        Files.writeString(tempDir.resolve("dep.evo"), "EXPORT HARVEST:\n  NOP\n");

        // lib.evo: requires DEP and uses DEP.HARVEST
        Files.writeString(tempDir.resolve("lib.evo"),
                ".REQUIRE \"dep.evo\" AS DEP\nEXPORT WORK:\n  JMPI DEP.HARVEST\n");

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
        Files.writeString(tempDir.resolve("dep.evo"), "EXPORT HARVEST:\n  NOP\n");

        // lib.evo: requires DEP, references DEP.HARVEST
        Files.writeString(tempDir.resolve("lib.evo"),
                ".REQUIRE \"dep.evo\" AS DEP\nEXPORT WORK:\n  JMPI DEP.HARVEST\n");

        String mainSource = ".IMPORT \"dep.evo\" AS D\n.IMPORT \"lib.evo\" AS LIB USING D AS DEP\nNOP\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        SemanticsResult result = compileThroughSemanticsWithSymbols(mainSource, mainPath);

        assertThat(result.diagnostics.hasErrors())
                .as("Expected no errors but got: %s", result.diagnostics.getDiagnostics())
                .isFalse();

        // Verify that DEP.HARVEST resolves from the LIB module context
        SymbolTable st = result.symbolTable;
        st.setCurrentModule("LIB");

        String depPath = tempDir.resolve("dep.evo").normalize().toString().replace('\\', '/');
        var resolved = st.resolve("DEP.HARVEST", depPath);
        assertThat(resolved).isPresent();
        assertThat(resolved.get().symbol().type()).isEqualTo(Symbol.Type.LABEL);
        assertThat(resolved.get().symbol().name()).isEqualToIgnoringCase("HARVEST");
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
                "EXPORT .PROC ADD_CONST REF X\n" +
                "  ADDI X AMOUNT\n" +
                "  RET\n" +
                ".ENDP\n");

        // user.evo: requires MATH, calls MATH.ADD_CONST
        Files.writeString(tempDir.resolve("user.evo"),
                ".REQUIRE \"math.evo\" AS MATH\n" +
                "EXPORT .PROC DO_WORK REF V\n" +
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

        String rootAliasChain = "";

        // Phase 0: Dependency scanning
        SourceRootResolver resolver = new SourceRootResolver(
                List.of(new SourceRoot(".", null)), tempDir);
        DependencyScanner scanner = new DependencyScanner(diagnostics, resolver);
        DependencyGraph graph = scanner.scan(mainSource, mainPath);
        if (diagnostics.hasErrors()) return new SemanticsResult(diagnostics, null);

        // Phase 1: Lex all modules
        Map<String, List<Token>> moduleTokens = new HashMap<>();
        for (ModuleDescriptor module : graph.topologicalOrder()) {
            if (module.id().path().equals(mainPath)) continue;
            String source = module.content();
            if (!source.endsWith("\n")) source += "\n";
            Lexer moduleLexer = new Lexer(source, diagnostics, module.sourcePath());
            List<Token> tokens = moduleLexer.scanTokens();
            Lexer.stripEofToken(tokens);
            moduleTokens.put(module.sourcePath(), tokens);
        }
        Lexer mainLexer = new Lexer(mainSource, diagnostics, mainPath);
        List<Token> mainTokens = new ArrayList<>(mainLexer.scanTokens());

        // Phase 2: Preprocessing (with root alias chain)
        PreProcessorHandlerRegistry ppRegistry = new PreProcessorHandlerRegistry();
        ppRegistry.register(".SOURCE", new SourceDirectiveHandler());
        ppRegistry.register(".MACRO", new MacroDirectiveHandler());
        ppRegistry.register(".POP_CTX", new PopCtxPreProcessorHandler());
        ppRegistry.register(".IMPORT", new ImportSourceHandler());
        ppRegistry.register(":", new org.evochora.compiler.features.label.ColonLabelHandler());
        // Pre-lex .SOURCE files
        Map<String, List<Token>> sourceTokens = new java.util.HashMap<>();
        for (Map.Entry<String, String> entry : scanner.sourceContents().entrySet()) {
            String srcPath = entry.getKey();
            String srcContent = entry.getValue();
            if (!srcContent.endsWith("\n")) srcContent += "\n";
            Lexer srcLexer = new Lexer(srcContent, diagnostics, srcPath);
            List<Token> srcTokens = srcLexer.scanTokens();
            Lexer.stripEofToken(srcTokens);
            sourceTokens.put(srcPath, srcTokens);
        }
        PreProcessorContext ppContext = new PreProcessorContext(rootAliasChain, moduleTokens, sourceTokens);
        PreProcessor preProcessor = new PreProcessor(mainTokens, diagnostics, resolver,
                ppRegistry, ppContext);
        List<Token> processedTokens = preProcessor.expand().tokens();
        if (diagnostics.hasErrors()) return new SemanticsResult(diagnostics, null);

        // Phase 3: Parsing
        Parser parser = new Parser(processedTokens, diagnostics, allHandlers());
        List<AstNode> ast = parser.parse();
        if (diagnostics.hasErrors()) return new SemanticsResult(diagnostics, null);

        // Phase 4: Semantic analysis (uses rootAliasChain instead of fileToModule)
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable, graph, mainPath, rootAliasChain, TestRegistries.analysisRegistry(symbolTable, diagnostics));
        analyzer.analyze(ast);

        return new SemanticsResult(diagnostics, symbolTable);
    }

    private static ParserStatementRegistry allHandlers() {
        ParserStatementRegistry reg = new ParserStatementRegistry();
        reg.register(".DEFINE", new DefineDirectiveHandler());
        reg.register(".REG", new RegDirectiveHandler());
        reg.register(".PROC", new ProcDirectiveHandler());
        reg.register(".PREG", new PregDirectiveHandler());
        reg.register(".ORG", new OrgDirectiveHandler());
        reg.register(".DIR", new DirDirectiveHandler());
        reg.register(".PLACE", new PlaceDirectiveHandler());
        reg.register(".IMPORT", new ImportDirectiveHandler());
        reg.register(".REQUIRE", new RequireDirectiveHandler());
        reg.register(".PUSH_CTX", new PushCtxDirectiveHandler());
        reg.register(".POP_CTX", new PopCtxDirectiveHandler());
        reg.register(".LABEL", new org.evochora.compiler.features.label.LabelDirectiveHandler());
        reg.register("CALL", new org.evochora.compiler.features.proc.CallStatementHandler());
        reg.registerDefault(new org.evochora.compiler.features.instruction.InstructionParsingHandler());
        return reg;
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
