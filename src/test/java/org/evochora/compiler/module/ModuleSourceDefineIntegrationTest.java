package org.evochora.compiler.module;

import org.evochora.compiler.api.SourceRoot;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.module.DependencyGraph;
import org.evochora.compiler.frontend.module.DependencyScanner;
import org.evochora.compiler.frontend.module.ModuleDescriptor;
import org.evochora.compiler.frontend.module.SourceRootResolver;
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
import org.evochora.compiler.model.ast.InstructionNode;
import org.evochora.compiler.model.ast.TypedLiteralNode;
import org.evochora.compiler.frontend.postprocess.AstPostProcessor;
import org.evochora.compiler.features.ctx.PopCtxPreProcessorHandler;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;
import org.evochora.compiler.frontend.preprocessor.PreProcessorHandlerRegistry;
import org.evochora.compiler.frontend.preprocessor.PreProcessorResult;
import org.evochora.compiler.features.importdir.ImportSourceHandler;
import org.evochora.compiler.features.macro.MacroDirectiveHandler;
import org.evochora.compiler.features.source.SourceDirectiveHandler;
import org.evochora.compiler.frontend.semantics.ModuleContextTracker;

import org.evochora.compiler.TestRegistries;
import org.evochora.compiler.frontend.semantics.SemanticAnalyzer;
import org.evochora.compiler.frontend.semantics.SymbolTable;
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
 * Integration tests for {@code .SOURCE} + {@code .DEFINE} in multi-module compilation.
 * Exercises the full pipeline from Phase 0 (dependency scanning) through Phase 6 (AST post-processing).
 */
class ModuleSourceDefineIntegrationTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void initInstructionSet() {
        org.evochora.runtime.isa.Instruction.init();
    }

    @Test
    @Tag("integration")
    void singleModuleWithSourcedConstants_resolvesCorrectly() throws Exception {
        Files.writeString(tempDir.resolve("consts.evo"),
                ".DEFINE FOO DATA:42\n");

        Files.writeString(tempDir.resolve("lib.evo"),
                ".SOURCE \"consts.evo\"\n" +
                "EXPORT .PROC WORK REF X\n" +
                "  SETI X FOO\n" +
                "  RET\n" +
                ".ENDP\n");

        String mainSource = ".IMPORT \"lib.evo\" AS LIB\nSETI %DR0 DATA:1\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        PostProcessResult result = compileThroughPostProcess(mainSource, mainPath);

        assertThat(result.diagnostics.hasErrors())
                .as("Expected no errors but got: %s", result.diagnostics.getDiagnostics())
                .isFalse();
    }

    @Test
    @Tag("integration")
    void twoModulesSourceSameDefineFile_noCollision() throws Exception {
        Files.writeString(tempDir.resolve("consts.evo"),
                ".DEFINE LIMIT DATA:99\n");

        Files.writeString(tempDir.resolve("mod_a.evo"),
                ".SOURCE \"consts.evo\"\n" +
                "EXPORT .PROC A_WORK REF X\n" +
                "  SETI X LIMIT\n" +
                "  RET\n" +
                ".ENDP\n");

        Files.writeString(tempDir.resolve("mod_b.evo"),
                ".SOURCE \"consts.evo\"\n" +
                "EXPORT .PROC B_WORK REF X\n" +
                "  SETI X LIMIT\n" +
                "  RET\n" +
                ".ENDP\n");

        String mainSource = ".IMPORT \"mod_a.evo\" AS A\n.IMPORT \"mod_b.evo\" AS B\nNOP\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        PostProcessResult result = compileThroughPostProcess(mainSource, mainPath);

        assertThat(result.diagnostics.hasErrors())
                .as("Expected no errors but got: %s", result.diagnostics.getDiagnostics())
                .isFalse();

        // Verify LIMIT was actually resolved to DATA:99 in both modules
        List<InstructionNode> setiNodes = new ArrayList<>();
        for (AstNode node : result.ast) {
            collectInstructions(node, "SETI", setiNodes);
        }
        assertThat(setiNodes).hasSize(2);
        assertThat(setiNodes).allSatisfy(seti -> {
            assertThat(seti.arguments()).hasSizeGreaterThanOrEqualTo(2);
            assertThat(seti.arguments().get(1)).isInstanceOf(TypedLiteralNode.class);
            assertThat(((TypedLiteralNode) seti.arguments().get(1)).value()).isEqualTo(99);
        });
    }

    @Test
    @Tag("integration")
    void twoModulesWithDifferentValuesForSameConstant_resolvesPerModule() throws Exception {
        // Each module has its own constants file with a different value for STEP
        Files.writeString(tempDir.resolve("fast_config.evo"),
                ".DEFINE STEP DATA:10\n");

        Files.writeString(tempDir.resolve("slow_config.evo"),
                ".DEFINE STEP DATA:1\n");

        Files.writeString(tempDir.resolve("fast.evo"),
                ".SOURCE \"fast_config.evo\"\n" +
                "EXPORT .PROC FAST_MOVE REF X\n" +
                "  ADDI X STEP\n" +
                "  RET\n" +
                ".ENDP\n");

        Files.writeString(tempDir.resolve("slow.evo"),
                ".SOURCE \"slow_config.evo\"\n" +
                "EXPORT .PROC SLOW_MOVE REF X\n" +
                "  ADDI X STEP\n" +
                "  RET\n" +
                ".ENDP\n");

        String mainSource = ".IMPORT \"fast.evo\" AS FAST\n.IMPORT \"slow.evo\" AS SLOW\nNOP\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        PostProcessResult result = compileThroughPostProcess(mainSource, mainPath);

        assertThat(result.diagnostics.hasErrors())
                .as("Expected no errors but got: %s", result.diagnostics.getDiagnostics())
                .isFalse();

        // Find the ADDI instructions and verify their constant values differ
        List<InstructionNode> addiNodes = new ArrayList<>();
        for (AstNode node : result.ast) {
            collectInstructions(node, "ADDI", addiNodes);
        }
        assertThat(addiNodes).hasSize(2);

        // Extract resolved constant values
        TypedLiteralNode fastStep = (TypedLiteralNode) addiNodes.get(0).arguments().get(1);
        TypedLiteralNode slowStep = (TypedLiteralNode) addiNodes.get(1).arguments().get(1);

        assertThat(fastStep.value()).isEqualTo(10);
        assertThat(slowStep.value()).isEqualTo(1);
    }

    @Test
    @Tag("integration")
    void sourcedConstantResolvedInInstruction() throws Exception {
        Files.writeString(tempDir.resolve("consts.evo"),
                ".DEFINE MAX DATA:255\n");

        Files.writeString(tempDir.resolve("lib.evo"),
                ".SOURCE \"consts.evo\"\n" +
                "EXPORT .PROC INIT REF X\n" +
                "  SETI X MAX\n" +
                "  RET\n" +
                ".ENDP\n");

        String mainSource = ".IMPORT \"lib.evo\" AS LIB\nCALL LIB.INIT REF %DR0\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        PostProcessResult result = compileThroughPostProcess(mainSource, mainPath);

        assertThat(result.diagnostics.hasErrors())
                .as("Expected no errors but got: %s", result.diagnostics.getDiagnostics())
                .isFalse();

        // Find the SETI instruction inside the proc and verify MAX was resolved
        List<InstructionNode> setiNodes = new ArrayList<>();
        for (AstNode node : result.ast) {
            collectInstructions(node, "SETI", setiNodes);
        }

        // At least one SETI should have a TypedLiteralNode with value 255
        boolean found = setiNodes.stream().anyMatch(seti ->
                seti.arguments().size() >= 2
                && seti.arguments().get(1) instanceof TypedLiteralNode tln
                && tln.value() == 255);
        assertThat(found).as("SETI with resolved constant MAX=255 not found").isTrue();
    }

    @Test
    @Tag("integration")
    void circularSourceDetected() throws Exception {
        // a.evo sources b.evo, b.evo sources a.evo
        // Test at preprocessor level directly (DependencyScanner doesn't handle .SOURCE cycles)
        Files.writeString(tempDir.resolve("a.evo"),
                ".SOURCE \"b.evo\"\n.DEFINE A_VAL DATA:1\n");
        Files.writeString(tempDir.resolve("b.evo"),
                ".SOURCE \"a.evo\"\n.DEFINE B_VAL DATA:2\n");

        String mainSource = ".SOURCE \"a.evo\"\nNOP\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(mainSource, diagnostics, mainPath);
        List<Token> tokens = new ArrayList<>(lexer.scanTokens());
        SourceRootResolver circularResolver = new SourceRootResolver(
                List.of(new SourceRoot(".", null)), tempDir);
        PreProcessorHandlerRegistry registry = new PreProcessorHandlerRegistry();
        registry.register(".SOURCE", new SourceDirectiveHandler());
        registry.register(":", new org.evochora.compiler.features.label.ColonLabelHandler());
        PreProcessor preProcessor = new PreProcessor(tokens, diagnostics, circularResolver,
                registry, new PreProcessorContext());
        preProcessor.expand();

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.message().toLowerCase().contains("circular")))
                .as("Expected circular .SOURCE error")
                .isTrue();
    }

    @Test
    @Tag("integration")
    void mainFileSourcesConstantsDirectly() throws Exception {
        Files.writeString(tempDir.resolve("consts.evo"),
                ".DEFINE INIT_VAL DATA:7\n");

        String mainSource = ".SOURCE \"consts.evo\"\nSETI %DR0 INIT_VAL\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        PostProcessResult result = compileThroughPostProcess(mainSource, mainPath);

        assertThat(result.diagnostics.hasErrors())
                .as("Expected no errors but got: %s", result.diagnostics.getDiagnostics())
                .isFalse();

        // Verify INIT_VAL was resolved to DATA:7
        List<InstructionNode> setiNodes = new ArrayList<>();
        for (AstNode node : result.ast) {
            collectInstructions(node, "SETI", setiNodes);
        }
        assertThat(setiNodes).isNotEmpty();
        boolean found = setiNodes.stream().anyMatch(seti ->
                seti.arguments().size() >= 2
                && seti.arguments().get(1) instanceof TypedLiteralNode tln
                && tln.value() == 7);
        assertThat(found).as("SETI with resolved constant INIT_VAL=7 not found").isTrue();
    }

    // --- Helper infrastructure ---

    private record PostProcessResult(DiagnosticsEngine diagnostics, List<AstNode> ast) {}

    /**
     * Runs compilation through Phase 6 (AstPostProcessor), returning the processed AST.
     */
    private PostProcessResult compileThroughPostProcess(String mainSource, String mainPath) {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();

        String rootAliasChain = "";

        // Phase 0: Dependency scanning
        SourceRootResolver resolver = new SourceRootResolver(
                List.of(new SourceRoot(".", null)), tempDir);
        DependencyScanner scanner = new DependencyScanner(diagnostics, resolver);
        DependencyGraph graph = scanner.scan(mainSource, mainPath);
        if (diagnostics.hasErrors()) return new PostProcessResult(diagnostics, List.of());

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

        // Phase 2: Preprocessing (with root alias chain for alias chain tracking)
        PreProcessorHandlerRegistry ppRegistry = new PreProcessorHandlerRegistry();
        ppRegistry.register(".SOURCE", new SourceDirectiveHandler());
        ppRegistry.register(".MACRO", new MacroDirectiveHandler());
        ppRegistry.register(".POP_CTX", new PopCtxPreProcessorHandler());
        ppRegistry.register(".IMPORT", new ImportSourceHandler());
        ppRegistry.register(":", new org.evochora.compiler.features.label.ColonLabelHandler());
        PreProcessorContext ppContext = new PreProcessorContext(rootAliasChain, moduleTokens);
        PreProcessor preProcessor = new PreProcessor(mainTokens, diagnostics, resolver,
                ppRegistry, ppContext);
        PreProcessorResult ppResult = preProcessor.expand();
        if (diagnostics.hasErrors()) return new PostProcessResult(diagnostics, List.of());

        // Phase 3: Parsing
        Parser parser = new Parser(ppResult.tokens(), diagnostics, allHandlers());
        List<AstNode> ast = new ArrayList<>(parser.parse());
        if (diagnostics.hasErrors()) return new PostProcessResult(diagnostics, ast);

        // Phase 4: Semantic analysis (uses rootAliasChain instead of fileToModule)
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable, graph, mainPath, rootAliasChain, TestRegistries.analysisRegistry(symbolTable, diagnostics));
        analyzer.analyze(ast);
        if (diagnostics.hasErrors()) return new PostProcessResult(diagnostics, ast);

        // Phase 6: AST Post-Processing (skip Phase 5 TokenMap — not needed for these tests)
        ModuleContextTracker tracker = new ModuleContextTracker(symbolTable);
        symbolTable.setCurrentModule(rootAliasChain);
        AstPostProcessor postProcessor = new AstPostProcessor(symbolTable, tracker, TestRegistries.postProcessRegistry());
        for (int i = 0; i < ast.size(); i++) {
            ast.set(i, postProcessor.process(ast.get(i)));
        }

        return new PostProcessResult(diagnostics, ast);
    }

    private void collectInstructions(AstNode node, String opcode, List<InstructionNode> result) {
        if (node == null) return;
        if (node instanceof InstructionNode instr && instr.opcode().equalsIgnoreCase(opcode)) {
            result.add(instr);
        }
        for (AstNode child : node.getChildren()) {
            collectInstructions(child, opcode, result);
        }
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
        return reg;
    }
}
