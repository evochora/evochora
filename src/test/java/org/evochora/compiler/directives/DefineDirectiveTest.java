package org.evochora.compiler.directives;

import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.InstructionNode;
import org.evochora.compiler.frontend.parser.ast.NumberLiteralNode;
import org.evochora.compiler.frontend.parser.ast.RegisterNode;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

public class DefineDirectiveTest {
    @Test
    void testDefineDirectiveAddsToSymbolTable() {
        // Arrange
        String source = ".DEFINE MY_CONST DATA:123";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics);

        // Act
        parser.parse();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();

        var symbolTable = parser.getSymbolTable();
        assertThat(symbolTable).hasSize(1);
        assertThat(symbolTable).containsKey("MY_CONST");

        Token valueToken = symbolTable.get("MY_CONST");
        assertThat(valueToken.type()).isEqualTo(TokenType.NUMBER);
        assertThat(valueToken.value()).isEqualTo(123);
    }

    @Test
    void testParserUsesDefinedConstant() {
        // Arrange
        String source = String.join("\n",
                ".DEFINE MY_CONST 123",
                "SETI %DR0 MY_CONST"
        );
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

        InstructionNode seti = (InstructionNode) ast.get(0);
        assertThat(seti.opcode().text()).isEqualTo("SETI");
        assertThat(seti.arguments()).hasSize(2);
        assertThat(seti.arguments().get(0)).isInstanceOf(RegisterNode.class);
        assertThat(seti.arguments().get(1)).isInstanceOf(NumberLiteralNode.class);

        NumberLiteralNode literal = (NumberLiteralNode) seti.arguments().get(1);
        assertThat(literal.numberToken().value()).isEqualTo(123);
    }
}
