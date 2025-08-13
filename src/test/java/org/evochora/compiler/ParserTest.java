package org.evochora.compiler;

import org.evochora.compiler.core.Lexer;
import org.evochora.compiler.core.Parser;
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

            // Prüfe, ob die Konstante korrekt in der Symboltabelle des Parsers eingetragen wurde.
            var symbolTable = parser.getSymbolTable();
            assertThat(symbolTable).hasSize(1);
            assertThat(symbolTable).containsKey("MY_CONST");

            Token valueToken = symbolTable.get("MY_CONST");
            assertThat(valueToken.type()).isEqualTo(org.evochora.compiler.core.TokenType.NUMBER);
            assertThat(valueToken.value()).isEqualTo(123);
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
        // Es bleibt genau eine Instruktion übrig (die Direktive erzeugt keinen AST-Knoten)
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

    @Test
    void testRegDirectiveAndAliasUsage() {
        // Arrange
        String source = String.join("\n",
                ".REG STACK_POINTER %DR15",
                "SETI STACK_POINTER 42"
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
        InstructionNode seti = (InstructionNode) ast.get(0);
        assertThat(seti.arguments()).hasSize(2);

        // Das erste Argument sollte als RegisterNode aufgelöst werden,
        // obwohl im Quellcode ein Identifier steht.
        assertThat(seti.arguments().get(0)).isInstanceOf(RegisterNode.class);
        RegisterNode reg = (RegisterNode) seti.arguments().get(0);
        assertThat(reg.registerToken().text()).isEqualTo("%DR15");
    }

    @Test
    void testParserProcedureBlock() {
        // Arrange
        String source = String.join("\n",
                ".PROC MY_PROC",
                "  NOP",
                ".ENDP"
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
        assertThat(ast.get(0)).isInstanceOf(org.evochora.compiler.core.ast.ProcedureNode.class);

        org.evochora.compiler.core.ast.ProcedureNode procNode = (org.evochora.compiler.core.ast.ProcedureNode) ast.get(0);
        assertThat(procNode.name().text()).isEqualTo("MY_PROC");

        List<AstNode> bodyWithoutNulls = procNode.body().stream().filter(Objects::nonNull).toList();
        assertThat(bodyWithoutNulls).hasSize(1);
        assertThat(bodyWithoutNulls.get(0)).isInstanceOf(InstructionNode.class);

        InstructionNode nopNode = (InstructionNode) bodyWithoutNulls.get(0);
        assertThat(nopNode.opcode().text()).isEqualTo("NOP");
    }

    @Test
    void testParserProcedureWithParameters() {
        // Arrange
        String source = String.join("\n",
                ".PROC ADD",
                "  .WITH %DR0 %DR1",
                "  ADDS",
                ".ENDP"
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
        assertThat(ast.get(0)).isInstanceOf(org.evochora.compiler.core.ast.ProcedureNode.class);

        org.evochora.compiler.core.ast.ProcedureNode procNode = (org.evochora.compiler.core.ast.ProcedureNode) ast.get(0);
        assertThat(procNode.name().text()).isEqualTo("ADD");
        assertThat(procNode.parameters()).hasSize(2);
        assertThat(procNode.parameters().get(0).text()).isEqualTo("%DR0");
        assertThat(procNode.parameters().get(1).text()).isEqualTo("%DR1");

        List<AstNode> bodyWithoutNulls = procNode.body().stream().filter(Objects::nonNull).toList();
        assertThat(bodyWithoutNulls).hasSize(1);
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