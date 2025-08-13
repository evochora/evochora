package org.evochora.compiler.phases;

import org.evochora.compiler.core.phases.Lexer;
import org.evochora.compiler.core.phases.Parser;
import org.evochora.compiler.core.Token;
import org.evochora.compiler.core.ast.AstNode;
import org.evochora.compiler.core.ast.InstructionNode;
import org.evochora.compiler.core.ast.NumberLiteralNode;
import org.evochora.compiler.core.ast.RegisterNode;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

public class ParserTest {

    @Test
    void testParserSimpleInstruction() {
        // Arrange
        String source = "SETI %DR0 42";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics);

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

    @Test
    void testParserLabelStatement() {
        // Arrange
        String source = "L1: NOP";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics);

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        assertThat(ast.get(0)).isInstanceOf(org.evochora.compiler.core.ast.LabelNode.class);

        org.evochora.compiler.core.ast.LabelNode labelNode = (org.evochora.compiler.core.ast.LabelNode) ast.get(0);
        assertThat(labelNode.labelToken().text()).isEqualTo("L1");
        assertThat(labelNode.statement()).isInstanceOf(InstructionNode.class);

        InstructionNode nopNode = (InstructionNode) labelNode.statement();
        assertThat(nopNode.opcode().text()).isEqualTo("NOP");
        assertThat(nopNode.arguments()).isEmpty();
    }

    @Test
    void testParserVectorLiteral() {
        // Arrange
        String source = "SETV %DR0 10|-20";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics);

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        InstructionNode setv = (InstructionNode) ast.get(0);
        assertThat(setv.arguments()).hasSize(2);
        assertThat(setv.arguments().get(1)).isInstanceOf(org.evochora.compiler.core.ast.VectorLiteralNode.class);

        org.evochora.compiler.core.ast.VectorLiteralNode vector = (org.evochora.compiler.core.ast.VectorLiteralNode) setv.arguments().get(1);
        assertThat(vector.components()).hasSize(2);
        assertThat(vector.components().get(0).value()).isEqualTo(10);
        assertThat(vector.components().get(1).value()).isEqualTo(-20);
    }
}