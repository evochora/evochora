package org.evochora.compiler.directives;

import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ParserStatementRegistry;
import org.evochora.compiler.features.reg.RegDirectiveHandler;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.InstructionNode;
import org.evochora.compiler.model.ast.RegisterNode;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.TestRegistries;
import org.evochora.compiler.model.ModuleContextTracker;
import org.evochora.compiler.frontend.semantics.SemanticAnalyzer;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.evochora.compiler.frontend.postprocess.AstPostProcessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the `.REG` directive for creating register aliases.
 * This test ensures that the parser correctly defines and resolves these aliases.
 * This is a unit test and does not require external resources.
 */
public class RegDirectiveTest {
    
    @BeforeAll
    static void setUp() {
        org.evochora.runtime.isa.Instruction.init();
    }
    
    /**
     * Verifies that the parser correctly handles a `.REG` directive and subsequent usage of the alias.
     * The test defines a register alias and then uses it in an instruction, checking that the
     * parser resolves the alias back to the original register in the AST.
     * This is a unit test for the parser.
     */
    @Test
    @Tag("unit")
    void testRegDirectiveAndAliasUsage() {
        // Arrange
        String source = String.join("\n",
                ".REG STACK_POINTER %DR7",
                "SETI STACK_POINTER DATA:42"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());

        // Act - Run full compiler pipeline up to AstPostProcessor
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Semantic Analysis - Populates symbol table with aliases
        String rootAliasChain = "";
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        symbolTable.registerModule(rootAliasChain, "<memory>");
        symbolTable.setCurrentModule(rootAliasChain);
        SemanticAnalyzer semanticAnalyzer = new SemanticAnalyzer(diagnostics, symbolTable, null, null, null, TestRegistries.analysisRegistry(symbolTable, diagnostics), new org.evochora.compiler.frontend.semantics.ModuleSetupRegistry());
        semanticAnalyzer.analyze(ast);

        // AST Post-Processing - Resolves register aliases
        AstPostProcessor astPostProcessor = new AstPostProcessor(symbolTable, new ModuleContextTracker(symbolTable), TestRegistries.postProcessRegistry());
        List<AstNode> processedAst = ast.stream()
            .map(node -> astPostProcessor.process(node))
            .toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(processedAst).hasSize(2);

        // First node should be the RegNode
        assertThat(processedAst.get(0)).isInstanceOf(org.evochora.compiler.features.reg.RegNode.class);

        // Second node should be the InstructionNode
        assertThat(processedAst.get(1)).isInstanceOf(InstructionNode.class);
        InstructionNode seti = (InstructionNode) processedAst.get(1);
        assertThat(seti.arguments()).hasSize(2);

        assertThat(seti.arguments().get(0)).isInstanceOf(RegisterNode.class);
        RegisterNode reg = (RegisterNode) seti.arguments().get(0);
        assertThat(reg.getName()).isEqualTo("%DR7");
    }

    /**
     * Verifies that out-of-bounds register indices are rejected at parse time.
     */
    @Test
    @Tag("unit")
    void testRegDirectiveWithInvalidRegister() {
        String source = ".REG COUNTER %DR99";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());

        parser.parse();

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.summary()).contains("out of bounds for DR bank");
    }

    /**
     * Verifies that PDR registers are not available outside a .PROC block.
     */
    @Test
    @Tag("unit")
    void testRegDirectivePdrOutsideProcIsRejected() {
        String source = ".REG COUNTER %PDR0";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());

        parser.parse();

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.summary()).contains("not available in the current scope");
    }

    /**
     * Verifies that FDR registers cannot be aliased — they are managed by the CALL binding mechanism.
     */
    @Test
    @Tag("unit")
    void testRegDirectiveFdrIsForbidden() {
        String source = ".REG PARAM %FDR0";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());

        parser.parse();

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.summary()).contains("cannot be aliased");
    }

    /**
     * Verifies that PDR registers are accepted inside a .PROC block.
     */
    @Test
    @Tag("unit")
    void testRegDirectivePdrInsideProcIsAccepted() {
        String source = String.join("\n",
                ".PROC MY_PROC",
                "  .REG %TMP %PDR0",
                ".ENDP"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());

        parser.parse();

        assertThat(diagnostics.hasErrors()).isFalse();
    }

    /**
     * Verifies that out-of-bounds PDR indices are rejected even inside a .PROC block.
     */
    @Test
    @Tag("unit")
    void testRegDirectivePdrBoundsInsideProc() {
        String source = String.join("\n",
                ".PROC MY_PROC",
                "  .REG %TMP %PDR99",
                ".ENDP"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());

        parser.parse();

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.summary()).contains("out of bounds for PDR bank");
    }

    /**
     * Verifies that the semantic analyzer correctly accepts location register aliases.
     */
    @Test
    @Tag("unit")
    void testRegDirectiveWithLocationRegister() {
        // Arrange - Test with valid location register
        String source = String.join("\n",
                ".REG POSITION %LR0",  // Valid: .REG now supports LR registers
                "DPLR POSITION"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());

        // Act - Run full compiler pipeline up to AstPostProcessor
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Semantic Analysis - Populates symbol table with aliases
        String rootAliasChain = "";
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        symbolTable.registerModule(rootAliasChain, "<memory>");
        symbolTable.setCurrentModule(rootAliasChain);
        SemanticAnalyzer semanticAnalyzer = new SemanticAnalyzer(diagnostics, symbolTable, null, null, null, TestRegistries.analysisRegistry(symbolTable, diagnostics), new org.evochora.compiler.frontend.semantics.ModuleSetupRegistry());
        semanticAnalyzer.analyze(ast);

        // AST Post-Processing - Resolves register aliases
        AstPostProcessor astPostProcessor = new AstPostProcessor(symbolTable, new ModuleContextTracker(symbolTable), TestRegistries.postProcessRegistry());
        List<AstNode> processedAst = ast.stream()
            .map(node -> astPostProcessor.process(node))
            .toList();

        // Assert - Should compile successfully
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(processedAst).hasSize(2);

        // First node should be the RegNode
        assertThat(processedAst.get(0)).isInstanceOf(org.evochora.compiler.features.reg.RegNode.class);

        // Second node should be the InstructionNode
        assertThat(processedAst.get(1)).isInstanceOf(InstructionNode.class);
        InstructionNode dplr = (InstructionNode) processedAst.get(1);
        assertThat(dplr.arguments()).hasSize(1);

        assertThat(dplr.arguments().get(0)).isInstanceOf(RegisterNode.class);
        RegisterNode reg = (RegisterNode) dplr.arguments().get(0);
        assertThat(reg.getName()).isEqualTo("%LR0");
    }

    /**
     * Verifies that PDR remains available after an inner .PROC block closes,
     * because the outer .PROC scope still holds a reference count.
     */
    @Test
    @Tag("unit")
    void testRegDirectiveNestedProcPdrStaysAvailable() {
        String source = String.join("\n",
                ".PROC OUTER",
                "  .PROC INNER",
                "  .ENDP",
                "  .REG %TMP %PDR0",
                ".ENDP"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());

        parser.parse();

        assertThat(diagnostics.hasErrors()).isFalse();
    }

    /**
     * Verifies that out-of-bounds LR indices are rejected.
     */
    @Test
    @Tag("unit")
    void testRegDirectiveLrOutOfBounds() {
        String source = ".REG POSITION %LR99";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());

        parser.parse();

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.summary()).contains("out of bounds for LR bank");
    }

    /**
     * Verifies that tokens with unknown register banks are not recognized as REGISTER tokens
     * by the lexer and thus rejected by the parser.
     */
    @Test
    @Tag("unit")
    void testRegDirectiveUnknownBank() {
        String source = ".REG X %XYZ0";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());

        parser.parse();

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.summary()).contains("Expected a register after the alias name in .REG");
    }

    /**
     * Verifies that tokens with non-numeric register indices are not recognized as REGISTER tokens
     * by the lexer and thus rejected by the parser.
     */
    @Test
    @Tag("unit")
    void testRegDirectiveInvalidRegisterIndex() {
        String source = ".REG X %DRabc";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());

        parser.parse();

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.summary()).contains("Expected a register after the alias name in .REG");
    }

    private static ParserStatementRegistry registry() {
        ParserStatementRegistry reg = new ParserStatementRegistry();
        reg.register(".REG", new RegDirectiveHandler());
        reg.register(".PROC", new org.evochora.compiler.features.proc.ProcDirectiveHandler());
        reg.registerDefault(new org.evochora.compiler.features.instruction.InstructionParsingHandler());
        return reg;
    }
}
