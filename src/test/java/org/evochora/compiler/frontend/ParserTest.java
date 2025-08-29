package org.evochora.compiler.frontend;

import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.ast.*;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.features.label.LabelNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contains unit tests for the {@link Parser}.
 * These tests verify that the parser correctly transforms a stream of tokens into an
 * Abstract Syntax Tree (AST), representing the grammatical structure of the source code.
 * These are unit tests and do not require external resources.
 */
public class ParserTest {

    /**
     * Verifies that the parser correctly builds an {@link InstructionNode} for a simple instruction
     * with register and numeric literal arguments. It checks the opcode, argument types, and their values.
     * This is a unit test for the parser.
     */
    @Test
    @Tag("unit")
    void testParserSimpleInstruction() {
        // Arrange
        String source = "SETI %DR0 42";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, Path.of("")); // KORREKTUR

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        assertThat(ast.get(0)).isInstanceOf(InstructionNode.class);

        InstructionNode setiNode = (InstructionNode) ast.get(0);
        assertThat(setiNode.opcode().text()).isEqualTo("SETI");
        assertThat(setiNode.arguments()).hasSize(2);
        assertThat(setiNode.arguments().get(0)).isInstanceOf(RegisterNode.class);
        assertThat(setiNode.arguments().get(1)).isInstanceOf(NumberLiteralNode.class);

        RegisterNode regArg = (RegisterNode) setiNode.arguments().get(0);
        assertThat(regArg.registerToken().text()).isEqualTo("%DR0");

        NumberLiteralNode numArg = (NumberLiteralNode) setiNode.arguments().get(1);
        assertThat(numArg.getValue()).isEqualTo(42);
    }

    /**
     * Verifies that the parser correctly handles a labeled statement, creating a {@link LabelNode}
     * that contains both the label's identifier and the associated instruction node.
     * This is a unit test for the parser.
     */
    @Test
    @Tag("unit")
    void testParserLabelStatement() {
        // Arrange
        String source = "L1: NOP";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, Path.of("")); // KORREKTUR

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        assertThat(ast.get(0)).isInstanceOf(LabelNode.class);

        LabelNode labelNode = (LabelNode) ast.get(0);
        assertThat(labelNode.labelToken().text()).isEqualTo("L1");
        assertThat(labelNode.statement()).isInstanceOf(InstructionNode.class);

        InstructionNode nopNode = (InstructionNode) labelNode.statement();
        assertThat(nopNode.opcode().text()).isEqualTo("NOP");
        assertThat(nopNode.arguments()).isEmpty();
    }

    /**
     * Verifies that the parser correctly parses a vector literal (e.g., `10|-20`) into a
     * {@link VectorLiteralNode} with the correct integer components.
     * This is a unit test for the parser.
     */
    @Test
    @Tag("unit")
    void testParserVectorLiteral() {
        // Arrange
        String source = "SETV %DR0 10|-20";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, Path.of("")); // KORREKTUR

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        InstructionNode setv = (InstructionNode) ast.get(0);
        assertThat(setv.arguments()).hasSize(2);
        assertThat(setv.arguments().get(1)).isInstanceOf(VectorLiteralNode.class);

        VectorLiteralNode vector = (VectorLiteralNode) setv.arguments().get(1);
        assertThat(vector.components()).hasSize(2);
        assertThat(vector.components().get(0).value()).isEqualTo(10);
        assertThat(vector.components().get(1).value()).isEqualTo(-20);
    }
}