package org.evochora.compiler.directives;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.TypedLiteralNode;
import org.evochora.compiler.frontend.parser.ast.InstructionNode;
import org.evochora.compiler.frontend.parser.features.def.DefineNode;
import org.evochora.compiler.frontend.semantics.SemanticAnalyzer;
import org.evochora.compiler.frontend.semantics.SymbolTable;
import org.evochora.compiler.frontend.postprocess.AstPostProcessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the parsing of the `.DEFINE` directive.
 * These tests ensure that the parser correctly creates an AST node for the directive.
 * These are unit tests and do not require external resources.
 */
public class DefineDirectiveTest {
    
    @BeforeAll
    static void setUp() {
        org.evochora.runtime.isa.Instruction.init();
    }
    
    /**
     * Verifies that the parser correctly parses a `.DEFINE` directive into a {@link DefineNode}.
     * The test checks that the constant's name and its value are correctly represented in the AST.
     * This is a unit test that involves the lexer and parser components.
     */
    @Test
    @Tag("unit")
    void testDefineDirectiveCreatesCorrectAstNode() {
        // Arrange
        String source = ".DEFINE MY_CONST DATA:123";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, Path.of("")); // KORREKTUR


        // Act
        // Wir filtern null-Werte heraus, da der Parser für leere Zeilen null zurückgibt.
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        assertThat(ast.get(0)).isInstanceOf(DefineNode.class);

        DefineNode defineNode = (DefineNode) ast.get(0);
        assertThat(defineNode.name().text()).isEqualTo("MY_CONST");
        assertThat(defineNode.value()).isInstanceOf(TypedLiteralNode.class);
    }
    
    /**
     * Verifies that constants defined by `.DEFINE` directives are correctly resolved
     * when used in instructions. This tests the full compiler pipeline up to AST post-processing.
     */
    @Test
    @Tag("unit")
    void testDefineDirectiveAndConstantUsage() {
        // Arrange
        String source = String.join("\n",
                ".DEFINE MAX_VALUE DATA:42",
                "SETI %DR0 MAX_VALUE"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, Path.of(""));

        // Act - Run full compiler pipeline up to AstPostProcessor
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();
        
        // Semantic Analysis - Populates symbol table with constants
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer semanticAnalyzer = new SemanticAnalyzer(diagnostics, symbolTable);
        semanticAnalyzer.analyze(ast);
        
        // AST Post-Processing - Resolves constants
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
        assertThat(processedAst).hasSize(2); // DefineNode + InstructionNode
        // First node should be the DefineNode
        assertThat(processedAst.get(0)).isInstanceOf(DefineNode.class);
        
        // Second node should be the InstructionNode with resolved constant
        assertThat(processedAst.get(1)).isInstanceOf(InstructionNode.class);
        InstructionNode seti = (InstructionNode) processedAst.get(1);
        assertThat(seti.arguments()).hasSize(2);

        assertThat(seti.arguments().get(1)).isInstanceOf(TypedLiteralNode.class);
        TypedLiteralNode constant = (TypedLiteralNode) seti.arguments().get(1);
        assertThat(constant.value().text()).isEqualTo("42");
    }
}