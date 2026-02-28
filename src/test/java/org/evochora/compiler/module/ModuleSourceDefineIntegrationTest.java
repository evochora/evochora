package org.evochora.compiler.module;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.module.DependencyGraph;
import org.evochora.compiler.frontend.module.DependencyScanner;
import org.evochora.compiler.frontend.module.ModuleDescriptor;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.InstructionNode;
import org.evochora.compiler.frontend.parser.ast.TypedLiteralNode;
import org.evochora.compiler.frontend.postprocess.AstPostProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.semantics.ModuleContextTracker;
import org.evochora.compiler.frontend.semantics.ModuleId;
import org.evochora.compiler.frontend.semantics.SemanticAnalyzer;
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
                ".PROC WORK EXPORT REF X\n" +
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
                ".PROC A_WORK EXPORT REF X\n" +
                "  SETI X LIMIT\n" +
                "  RET\n" +
                ".ENDP\n");

        Files.writeString(tempDir.resolve("mod_b.evo"),
                ".SOURCE \"consts.evo\"\n" +
                ".PROC B_WORK EXPORT REF X\n" +
                "  SETI X LIMIT\n" +
                "  RET\n" +
                ".ENDP\n");

        String mainSource = ".IMPORT \"mod_a.evo\" AS A\n.IMPORT \"mod_b.evo\" AS B\nNOP\n";
        String mainPath = tempDir.resolve("main.evo").toString();

        PostProcessResult result = compileThroughPostProcess(mainSource, mainPath);

        assertThat(result.diagnostics.hasErrors())
                .as("Expected no errors but got: %s", result.diagnostics.getDiagnostics())
                .isFalse();
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
                ".PROC FAST_MOVE EXPORT REF X\n" +
                "  ADDI X STEP\n" +
                "  RET\n" +
                ".ENDP\n");

        Files.writeString(tempDir.resolve("slow.evo"),
                ".SOURCE \"slow_config.evo\"\n" +
                ".PROC SLOW_MOVE EXPORT REF X\n" +
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

        assertThat(fastStep.value().text()).isEqualTo("10");
        assertThat(slowStep.value().text()).isEqualTo("1");
    }

    @Test
    @Tag("integration")
    void sourcedConstantResolvedInInstruction() throws Exception {
        Files.writeString(tempDir.resolve("consts.evo"),
                ".DEFINE MAX DATA:255\n");

        Files.writeString(tempDir.resolve("lib.evo"),
                ".SOURCE \"consts.evo\"\n" +
                ".PROC INIT EXPORT REF X\n" +
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
                && "255".equals(tln.value().text()));
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
        PreProcessor preProcessor = new PreProcessor(tokens, diagnostics, tempDir, null);
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
                && "7".equals(tln.value().text()));
        assertThat(found).as("SETI with resolved constant INIT_VAL=7 not found").isTrue();
    }

    // --- Helper infrastructure ---

    private record PostProcessResult(DiagnosticsEngine diagnostics, List<AstNode> ast) {}

    /**
     * Runs compilation through Phase 6 (AstPostProcessor), returning the processed AST.
     */
    private PostProcessResult compileThroughPostProcess(String mainSource, String mainPath) {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();

        // Phase 0: Dependency scanning
        DependencyScanner scanner = new DependencyScanner(diagnostics);
        DependencyGraph graph = scanner.scan(mainSource, mainPath, tempDir);
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

        // Phase 2: Preprocessing
        PreProcessor preProcessor = new PreProcessor(mainTokens, diagnostics, tempDir,
                moduleTokens.isEmpty() ? null : moduleTokens);
        List<Token> processedTokens = preProcessor.expand();
        if (diagnostics.hasErrors()) return new PostProcessResult(diagnostics, List.of());

        // Phase 3: Parsing
        Parser parser = new Parser(processedTokens, diagnostics);
        List<AstNode> ast = new ArrayList<>(parser.parse());
        if (diagnostics.hasErrors()) return new PostProcessResult(diagnostics, ast);

        // Build file-to-module mapping
        Map<String, ModuleId> fileToModule = new HashMap<>();
        for (ModuleDescriptor module : graph.topologicalOrder()) {
            fileToModule.put(module.sourcePath(), module.id());
        }

        // Phase 4: Semantic analysis
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable, graph, mainPath, fileToModule);
        analyzer.analyze(ast);
        if (diagnostics.hasErrors()) return new PostProcessResult(diagnostics, ast);

        // Phase 6: AST Post-Processing (skip Phase 5 TokenMap — not needed for these tests)
        Map<String, String> registerAliases = new HashMap<>();
        parser.getGlobalRegisterAliases().forEach((name, token) -> registerAliases.put(name, token.text()));

        ModuleContextTracker tracker = new ModuleContextTracker(symbolTable, fileToModule);
        AstPostProcessor postProcessor = new AstPostProcessor(symbolTable, registerAliases, tracker);
        for (int i = 0; i < ast.size(); i++) {
            ast.set(i, postProcessor.process(ast.get(i)));
        }

        return new PostProcessResult(diagnostics, ast);
    }

    private void collectInstructions(AstNode node, String opcode, List<InstructionNode> result) {
        if (node == null) return;
        if (node instanceof InstructionNode instr && instr.opcode().text().equalsIgnoreCase(opcode)) {
            result.add(instr);
        }
        for (AstNode child : node.getChildren()) {
            collectInstructions(child, opcode, result);
        }
    }
}
