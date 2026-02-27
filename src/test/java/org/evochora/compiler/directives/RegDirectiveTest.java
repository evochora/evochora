package org.evochora.compiler.directives;

import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.InstructionNode;
import org.evochora.compiler.frontend.parser.ast.RegisterNode;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.semantics.SemanticAnalyzer;
import org.evochora.compiler.frontend.semantics.SymbolTable;
import org.evochora.compiler.frontend.postprocess.AstPostProcessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
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
        Parser parser = new Parser(tokens, diagnostics); // KORREKTUR

        // Act - Run full compiler pipeline up to AstPostProcessor
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();
        
        // Semantic Analysis - Populates symbol table with aliases
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer semanticAnalyzer = new SemanticAnalyzer(diagnostics, symbolTable);
        semanticAnalyzer.analyze(ast);
        
        // AST Post-Processing - Resolves register aliases
        // Extract register aliases from parser (same as the real compiler does)
        Map<String, String> registerAliases = new HashMap<>();
        Map<String, org.evochora.compiler.frontend.lexer.Token> parserAliases = parser.getGlobalRegisterAliases();
        
        parserAliases.forEach((aliasName, registerToken) -> {
            registerAliases.put(aliasName, registerToken.text());
        });
        
        AstPostProcessor astPostProcessor = new AstPostProcessor(symbolTable, registerAliases);
        List<AstNode> processedAst = ast.stream()
            .map(node -> astPostProcessor.process(node))
            .toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(processedAst).hasSize(2);
        
        // First node should be the RegNode
        assertThat(processedAst.get(0)).isInstanceOf(org.evochora.compiler.frontend.parser.features.reg.RegNode.class);
        
        // Second node should be the InstructionNode
        assertThat(processedAst.get(1)).isInstanceOf(InstructionNode.class);
        InstructionNode seti = (InstructionNode) processedAst.get(1);
        assertThat(seti.arguments()).hasSize(2);

        assertThat(seti.arguments().get(0)).isInstanceOf(RegisterNode.class);
        RegisterNode reg = (RegisterNode) seti.arguments().get(0);
        assertThat(reg.registerToken().text()).isEqualTo("%DR7");
    }

    /**
     * Verifies that the semantic analyzer correctly rejects invalid register references.
     * This test ensures that our enhanced validation catches out-of-bounds registers.
     */
    @Test
    @Tag("unit")
    void testRegDirectiveWithInvalidRegister() {
        // Arrange - Test with out-of-bounds register
        String source = String.join("\n",
                ".REG COUNTER %DR99",  // Invalid: DR99 doesn't exist (only DR0-DR7)
                "SETI COUNTER DATA:42"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics);

        // Act - Run semantic analysis
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer semanticAnalyzer = new SemanticAnalyzer(diagnostics, symbolTable);
        semanticAnalyzer.analyze(ast);

        // Assert - Should have compilation error
        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.summary()).contains("Invalid register '%DR99'");
        assertThat(diagnostics.summary()).contains(String.format(".REG directive supports data registers %%DR0-%%DR%d and location registers %%LR0-%%LR%d", 
            org.evochora.runtime.Config.NUM_DATA_REGISTERS - 1, 
            org.evochora.runtime.Config.NUM_LOCATION_REGISTERS - 1));
    }

    /**
     * Verifies that the semantic analyzer correctly rejects non-DR/LR register types.
     */
    @Test
    @Tag("unit")
    void testRegDirectiveWithNonDRRegister() {
        // Arrange - Test with non-DR/LR register (PR and FPR are not allowed in .REG)
        String source = String.join("\n",
                ".REG COUNTER %PR0",  // Invalid: .REG only supports DR and LR registers
                "SETI COUNTER DATA:42"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics);

        // Act - Run semantic analysis
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer semanticAnalyzer = new SemanticAnalyzer(diagnostics, symbolTable);
        semanticAnalyzer.analyze(ast);

        // Assert - Should have compilation error
        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.summary()).contains("Invalid register '%PR0'");
        assertThat(diagnostics.summary()).contains(String.format(".REG directive supports data registers %%DR0-%%DR%d and location registers %%LR0-%%LR%d", 
            org.evochora.runtime.Config.NUM_DATA_REGISTERS - 1, 
            org.evochora.runtime.Config.NUM_LOCATION_REGISTERS - 1));
    }

    /**
     * Verifies that the semantic analyzer correctly rejects invalid register types.
     */
    @Test
    @Tag("unit")
    void testRegDirectiveWithInvalidRegisterType() {
        // Arrange - Test with invalid register type
        String source = String.join("\n",
                ".REG COUNTER %PR0",  // Invalid: .REG only supports DR registers, not PR registers
                "SETI COUNTER DATA:42"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics);

        // Act - Run semantic analysis
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer semanticAnalyzer = new SemanticAnalyzer(diagnostics, symbolTable);
        semanticAnalyzer.analyze(ast);

        // Assert - Should have compilation error
        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.summary()).contains("Invalid register '%PR0'");
        assertThat(diagnostics.summary()).contains(String.format(".REG directive supports data registers %%DR0-%%DR%d and location registers %%LR0-%%LR%d", 
            org.evochora.runtime.Config.NUM_DATA_REGISTERS - 1, 
            org.evochora.runtime.Config.NUM_LOCATION_REGISTERS - 1));
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
        Parser parser = new Parser(tokens, diagnostics);

        // Act - Run full compiler pipeline up to AstPostProcessor
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();
        
        // Semantic Analysis - Populates symbol table with aliases
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer semanticAnalyzer = new SemanticAnalyzer(diagnostics, symbolTable);
        semanticAnalyzer.analyze(ast);
        
        // AST Post-Processing - Resolves register aliases
        Map<String, String> registerAliases = new HashMap<>();
        Map<String, org.evochora.compiler.frontend.lexer.Token> parserAliases = parser.getGlobalRegisterAliases();
        
        parserAliases.forEach((aliasName, registerToken) -> {
            registerAliases.put(aliasName, registerToken.text());
        });
        
        AstPostProcessor astPostProcessor = new AstPostProcessor(symbolTable, registerAliases);
        List<AstNode> processedAst = ast.stream()
            .map(node -> astPostProcessor.process(node))
            .toList();

        // Assert - Should compile successfully
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(processedAst).hasSize(2);
        
        // First node should be the RegNode
        assertThat(processedAst.get(0)).isInstanceOf(org.evochora.compiler.frontend.parser.features.reg.RegNode.class);
        
        // Second node should be the InstructionNode
        assertThat(processedAst.get(1)).isInstanceOf(InstructionNode.class);
        InstructionNode dplr = (InstructionNode) processedAst.get(1);
        assertThat(dplr.arguments()).hasSize(1);

        assertThat(dplr.arguments().get(0)).isInstanceOf(RegisterNode.class);
        RegisterNode reg = (RegisterNode) dplr.arguments().get(0);
        assertThat(reg.registerToken().text()).isEqualTo("%LR0");
    }
}
