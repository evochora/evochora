package org.evochora.compiler.features.control;

import org.evochora.compiler.FeatureRegistry;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.compiler.api.TokenKind;
import org.evochora.compiler.StandardFeatures;
import org.evochora.compiler.TestLexers;
import org.evochora.compiler.TestRegistries;
import org.evochora.compiler.diagnostics.Diagnostic;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.module.ModuleContextTracker;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ParserStatementRegistry;
import org.evochora.compiler.frontend.postprocess.AstPostProcessor;
import org.evochora.compiler.frontend.semantics.ModuleSetupRegistry;
import org.evochora.compiler.frontend.semantics.ScopeTracker;
import org.evochora.compiler.frontend.semantics.SemanticAnalyzer;
import org.evochora.compiler.frontend.tokenmap.TokenMapContributorRegistry;
import org.evochora.compiler.frontend.tokenmap.TokenMapGenerator;
import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.InstructionNode;
import org.evochora.compiler.model.ast.RegisterNode;
import org.evochora.compiler.model.ast.TypedLiteralNode;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.evochora.compiler.model.token.Token;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the control block as a level of names: the block's name on the level around it,
 * its cases and {@code END} on the block's own level, and the visibility rule applied to them.
 * <p>
 * The sources run through the lexer, the parser, the semantic analysis and the AST
 * post-processing, each set up with the handlers of the standard features, as the compiler sets
 * them up. A name that resolves is asserted on the post-processed tree, where an identifier that
 * names a label carries the label's path. The sources are written as the preprocessor hands them
 * on, with a label as {@code .LABEL L}.
 */
@Tag("unit")
class ControlLevelTest {

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void theEndAndACaseResolveInsideTheBlockToTheirPaths() {
        Analyzed analyzed = analyze(
                ".CONTROL WALK",
                "  JMPI BLOCKED",
                "  JMPI END",
                ".CASE BLOCKED",
                "  NOP",
                ".ENDCONTROL");

        assertThat(analyzed.errors()).isEmpty();
        assertThat(analyzed.argument(2, 0)).isEqualTo("WALK.BLOCKED");
        assertThat(analyzed.argument(3, 0)).isEqualTo("WALK.END");
    }

    @Test
    void theBlockNameResolvesFromBeforeAndFromAfterTheBlock() {
        Analyzed analyzed = analyze(
                "JMPI WALK",
                ".CONTROL WALK",
                "  NOP",
                ".ENDCONTROL",
                "JMPI WALK");

        assertThat(analyzed.errors()).isEmpty();
        assertThat(analyzed.argument(1, 0)).isEqualTo("WALK");
        assertThat(analyzed.argument(5, 0)).isEqualTo("WALK");
    }

    @Test
    void aCaseIsNotReachedFromAfterTheBlockWithoutExport() {
        Analyzed analyzed = analyze(
                ".CONTROL WALK",
                ".CASE BLOCKED",
                "  NOP",
                ".ENDCONTROL",
                "JMPI WALK.BLOCKED");

        assertThat(analyzed.errors()).containsExactly(
                "5: Cannot use 'WALK.BLOCKED' as an argument: 'BLOCKED' of WALK is not marked EXPORT.");
    }

    @Test
    void anExportedCaseIsReachedFromAfterTheBlockByItsPath() {
        Analyzed analyzed = analyze(
                ".CONTROL WALK",
                "EXPORT .CASE BLOCKED",
                "  NOP",
                ".ENDCONTROL",
                "JMPI WALK.BLOCKED");

        assertThat(analyzed.errors()).isEmpty();
        assertThat(analyzed.argument(5, 0)).isEqualTo("WALK.BLOCKED");
    }

    @Test
    void endInANestedBlockIsTheInnerEndAndThePathReachesTheOuterEnd() {
        Analyzed analyzed = analyze(
                ".CONTROL WALK",
                "  .CONTROL INNER",
                "    JMPI END",
                "    JMPI WALK.END",
                "  .ENDCONTROL",
                ".ENDCONTROL");

        assertThat(analyzed.errors()).isEmpty();
        assertThat(analyzed.argument(3, 0)).isEqualTo("WALK.INNER.END");
        assertThat(analyzed.argument(4, 0)).isEqualTo("WALK.END");
    }

    @Test
    void aCaseIsALabelArgumentOfAConditionalJump() {
        Analyzed analyzed = analyze(
                ".CONTROL WALK",
                "  JFI %DR0 DATA:1 BLOCKED",
                ".CASE BLOCKED",
                "  NOP",
                ".ENDCONTROL");

        assertThat(analyzed.errors()).isEmpty();
        assertThat(analyzed.argument(2, 2)).isEqualTo("WALK.BLOCKED");
    }

    @Test
    void aLabelEndInTheBlockIsReportedAtTheLabelNamingTheEndOfTheBlock() {
        Analyzed analyzed = analyze(
                ".CONTROL WALK",
                "  NOP",
                ".LABEL END",
                "  NOP",
                ".ENDCONTROL");

        assertThat(analyzed.errors()).containsExactly(
                "3: Cannot define label 'END': the name is already used at main.evo:5.");
    }

    @Test
    void aLabelInTheHeadBeforeACaseOfItsNameIsReportedAtTheCase() {
        Analyzed analyzed = analyze(
                ".CONTROL WALK",
                ".LABEL BLOCKED",
                "  NOP",
                ".CASE BLOCKED",
                "  NOP",
                ".ENDCONTROL");

        assertThat(analyzed.errors()).containsExactly(
                "4: Cannot define case 'BLOCKED': the name is already used at main.evo:2.");
    }

    @Test
    void aSecondBlockOfOneNameOnOneLevelIsReportedAtTheSecond() {
        Analyzed analyzed = analyze(
                ".CONTROL WALK",
                "  NOP",
                ".ENDCONTROL",
                ".CONTROL WALK",
                "  NOP",
                ".ENDCONTROL");

        assertThat(analyzed.errors()).containsExactly(
                "4: Cannot define block 'WALK': the name is already used at main.evo:1.");
    }

    @Test
    void aLabelOfTheBlockIsVisibleInsideItAndNotFromOutsideWithoutExport() {
        Analyzed analyzed = analyze(
                ".CONTROL WALK",
                "  JMPI L",
                ".LABEL L",
                "  NOP",
                ".ENDCONTROL",
                "JMPI WALK.L");

        assertThat(analyzed.errors()).containsExactly(
                "6: Cannot use 'WALK.L' as an argument: 'L' of WALK is not marked EXPORT.");
    }

    @Test
    void anExportedLabelOfTheBlockIsReachedInsideByItsNameAndOutsideByItsPath() {
        Analyzed analyzed = analyze(
                ".CONTROL WALK",
                "  JMPI L",
                "EXPORT .LABEL L",
                "  NOP",
                ".ENDCONTROL",
                "JMPI WALK.L");

        assertThat(analyzed.errors()).isEmpty();
        assertThat(analyzed.argument(2, 0)).isEqualTo("WALK.L");
        assertThat(analyzed.argument(6, 0)).isEqualTo("WALK.L");
    }

    @Test
    void aConstantOfTheBlockIsVisibleInsideItAndNotFromOutsideWithoutExport() {
        Analyzed analyzed = analyze(
                ".CONTROL WALK",
                "  .CONST N DATA:5",
                "  SETI %DR0 N",
                ".ENDCONTROL",
                "SETI %DR0 WALK.N");

        assertThat(analyzed.errors()).containsExactly(
                "5: Cannot use 'WALK.N' as an argument: 'N' of WALK is not marked EXPORT.");
    }

    @Test
    void anExportedConstantOfTheBlockIsReachedInsideByItsNameAndOutsideByItsPath() {
        Analyzed analyzed = analyze(
                ".CONTROL WALK",
                "  EXPORT .CONST N DATA:5",
                "  SETI %DR0 N",
                ".ENDCONTROL",
                "SETI %DR0 WALK.N");

        assertThat(analyzed.errors()).isEmpty();
        assertThat(analyzed.instructionAt(3).arguments().get(1))
                .isInstanceOfSatisfying(TypedLiteralNode.class, value -> assertThat(value.value()).isEqualTo(5));
        assertThat(analyzed.instructionAt(5).arguments().get(1))
                .isInstanceOfSatisfying(TypedLiteralNode.class, value -> assertThat(value.value()).isEqualTo(5));
    }

    @Test
    void anImportInsideABlockIsReported() {
        Analyzed analyzed = analyze(
                ".CONTROL WALK",
                "  .IMPORT \"lib.evo\" AS LIB",
                ".ENDCONTROL");

        assertThat(analyzed.errors()).containsExactly(
                "2: .IMPORT may stand only at the module level.");
    }

    @Test
    void aProcedureInsideABlockIsReported() {
        Analyzed analyzed = analyze(
                ".CONTROL WALK",
                "  .PROC P",
                "    RET",
                "  .ENDPROC",
                ".ENDCONTROL");

        assertThat(analyzed.errors()).containsExactly(
                "2: .PROC may stand only at the module level.");
    }

    // --- helpers ---

    /**
     * The outcome of a source run through the frontend.
     *
     * @param ast         The post-processed statements, or the parsed ones if the analysis
     *                    reported an error and post-processing did not run.
     * @param diagnostics What the phases reported.
     */
    /**
     * A parameter written inside a control block of its procedure: the token map files it under
     * the procedure, the level that defines it, not under the block, which is what the visualizer
     * keys its parameter names by; and the post-processing binds it to the formal register as it
     * does outside the block.
     */
    @Test
    void aParameterUsedInsideABlockOfItsProcedureIsFiledUnderTheProcedureAndBoundToItsRegister() {
        Analyzed analyzed = analyze(
                ".PROC P REF X",
                "  .CONTROL WALK",
                "    ADDI X DATA:1",
                "  .ENDCONTROL",
                "  RET",
                ".ENDPROC");

        assertThat(analyzed.errors()).isEmpty();
        TokenInfo x = analyzed.tokenAt(3, "X");
        assertThat(x.tokenType()).isEqualTo(TokenKind.PARAMETER);
        assertThat(x.scope()).isEqualTo("P");
        AstNode argument = analyzed.instructionAt(3).arguments().get(0);
        assertThat(argument).isInstanceOf(RegisterNode.class);
        assertThat(((RegisterNode) argument).name()).isEqualTo("%FDR0");
    }

    /**
     * A register alias is the one name defined in pass 2, by the analysis handler of the
     * directive, in the scope pass 2 stands in: inside a block it belongs to the block, is bound
     * there, is unknown after the block, and a sibling block may define the same alias.
     */
    @Test
    void aRegisterAliasDefinedInABlockBelongsToTheBlock() {
        Analyzed analyzed = analyze(
                ".CONTROL WALK",
                "  .REG %TMP %DR1",
                "  INCR %TMP",
                ".ENDCONTROL",
                ".CONTROL RUN",
                "  .REG %TMP %DR2",
                "  INCR %TMP",
                ".ENDCONTROL",
                "INCR %TMP");

        assertThat(analyzed.errors()).containsExactly("9: Cannot use '%TMP' as an argument: the name is not defined.");
        Analyzed inside = analyze(
                ".CONTROL WALK",
                "  .REG %TMP %DR1",
                "  INCR %TMP",
                ".ENDCONTROL",
                ".CONTROL RUN",
                "  .REG %TMP %DR2",
                "  INCR %TMP",
                ".ENDCONTROL");
        assertThat(inside.errors()).isEmpty();
        assertThat(((RegisterNode) inside.instructionAt(3).arguments().getFirst()).name()).isEqualTo("%DR1");
        assertThat(((RegisterNode) inside.instructionAt(7).arguments().getFirst()).name()).isEqualTo("%DR2");
    }

    private record Analyzed(List<AstNode> ast, DiagnosticsEngine diagnostics, Map<SourceInfo, TokenInfo> tokenMap) {

        /** The token-map entry of the token with the given text on the given source line. */
        TokenInfo tokenAt(int line, String text) {
            return tokenMap.entrySet().stream()
                    .filter(e -> e.getKey().lineNumber() == line && e.getValue().tokenText().equals(text))
                    .map(Map.Entry::getValue)
                    .reduce((first, second) -> {
                        throw new AssertionError("More than one token '" + text + "' on line " + line);
                    })
                    .orElseThrow(() -> new AssertionError("No token '" + text + "' on line " + line));
        }

        /** The errors, each as {@code line: message}. */
        List<String> errors() {
            return diagnostics.getDiagnostics().stream()
                    .filter(d -> d.type() == Diagnostic.Type.ERROR)
                    .map(d -> d.lineNumber() + ": " + d.message())
                    .toList();
        }

        /** The one instruction on the given source line, wherever it stands in the tree. */
        InstructionNode instructionAt(int line) {
            List<InstructionNode> instructions = new ArrayList<>();
            collectInstructions(ast, instructions);
            return instructions.stream()
                    .filter(instruction -> instruction.sourceInfo().lineNumber() == line)
                    .reduce((first, second) -> {
                        throw new AssertionError("More than one instruction on line " + line);
                    })
                    .orElseThrow(() -> new AssertionError("No instruction on line " + line));
        }

        /** The text of the identifier the instruction on the given line has as the given argument. */
        String argument(int line, int index) {
            AstNode argument = instructionAt(line).arguments().get(index);
            assertThat(argument).isInstanceOf(IdentifierNode.class);
            return ((IdentifierNode) argument).text();
        }

        private static void collectInstructions(List<AstNode> nodes, List<InstructionNode> into) {
            for (AstNode node : nodes) {
                if (node instanceof InstructionNode instruction) {
                    into.add(instruction);
                }
                collectInstructions(node.getChildren(), into);
            }
        }
    }

    /**
     * Runs the lines, as the file {@code main.evo}, through the lexer, the parser, the semantic
     * analysis and, if the analysis reported no error, the token map and the AST post-processing,
     * with the handlers of the standard features registered as the compiler registers them.
     */
    private static Analyzed analyze(String... lines) {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<Token> tokens = new Lexer(String.join("\n", lines) + "\n", diagnostics, "main.evo", TestLexers.symbols())
                .scanTokens();
        FeatureRegistry features = new FeatureRegistry(new RuntimeInstructionSetAdapter());
        StandardFeatures.all().forEach(f -> f.register(features));
        ParserStatementRegistry registry = new ParserStatementRegistry();
        features.parserStatementHandlers().forEach(registry::register);
        features.parserBlocks().forEach(block -> registry.registerBlock(block.kind(), block.handler()));
        registry.registerDefault(features.defaultParserStatementHandler());
        List<AstNode> ast = new Parser(tokens, diagnostics, registry).parse();

        SymbolTable symbolTable = new SymbolTable(diagnostics);
        new SemanticAnalyzer(diagnostics, symbolTable, null, null,
                TestRegistries.analysisRegistry(symbolTable, diagnostics), new ModuleSetupRegistry())
                .analyze(ast);
        if (diagnostics.hasErrors()) {
            return new Analyzed(ast, diagnostics, Map.of());
        }
        TokenMapContributorRegistry tokenMapRegistry = new TokenMapContributorRegistry();
        tokenMapRegistry.registerAll(features.tokenMapContributors());
        Map<SourceInfo, TokenInfo> tokenMap = new TokenMapGenerator(symbolTable, diagnostics, tokenMapRegistry,
                new ModuleContextTracker(symbolTable)).generateAll(ast);
        AstPostProcessor postProcessor = new AstPostProcessor(symbolTable, new ModuleContextTracker(symbolTable),
                new ScopeTracker(symbolTable), TestRegistries.postProcessRegistry());
        return new Analyzed(postProcessor.process(ast), diagnostics, tokenMap);
    }
}
