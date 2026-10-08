package org.evochora.compiler.frontend;

import org.evochora.compiler.TestLexers;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ParserStatementRegistry;
import org.evochora.compiler.features.constdir.ConstDirectiveHandler;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.InstructionNode;
import org.evochora.compiler.model.ast.NumberLiteralNode;
import org.evochora.compiler.model.ast.RegisterNode;
import org.evochora.compiler.model.ast.VectorLiteralNode;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.features.constdir.ConstNode;
import org.evochora.compiler.features.label.LabelNode;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contains unit tests for the {@link Parser}.
 * These tests verify that the parser correctly transforms a stream of tokens into an
 * Abstract Syntax Tree (AST), representing the grammatical structure of the source code.
 * These are unit tests and do not require external resources.
 */
public class ParserTest {

    @BeforeAll
    static void initInstructions() {
        Instruction.init();
    }

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
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry()); // KORREKTUR

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        assertThat(ast.get(0)).isInstanceOf(InstructionNode.class);

        InstructionNode setiNode = (InstructionNode) ast.get(0);
        assertThat(setiNode.opcode()).isEqualTo("SETI");
        assertThat(setiNode.arguments()).hasSize(2);
        assertThat(setiNode.arguments().get(0)).isInstanceOf(RegisterNode.class);
        assertThat(setiNode.arguments().get(1)).isInstanceOf(NumberLiteralNode.class);

        RegisterNode regArg = (RegisterNode) setiNode.arguments().get(0);
        assertThat(regArg.name()).isEqualTo("%DR0");

        NumberLiteralNode numArg = (NumberLiteralNode) setiNode.arguments().get(1);
        assertThat(numArg.value()).isEqualTo(42);
    }

    /**
     * Verifies that the parser correctly handles a labeled statement, creating a {@link LabelNode}
     * with the label's identifier followed by the instruction node of the same line.
     * This is a unit test for the parser.
     */
    @Test
    @Tag("unit")
    void testParserLabelStatement() {
        // Arrange
        String source = ".LABEL L1 NOP";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(2);
        assertThat(ast.get(0)).isInstanceOf(LabelNode.class);

        LabelNode labelNode = (LabelNode) ast.get(0);
        assertThat(labelNode.name()).isEqualTo("L1");
        assertThat(ast.get(1)).isInstanceOf(InstructionNode.class);

        InstructionNode nopNode = (InstructionNode) ast.get(1);
        assertThat(nopNode.opcode()).isEqualTo("NOP");
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
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry()); // KORREKTUR

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        InstructionNode setv = (InstructionNode) ast.get(0);
        assertThat(setv.arguments()).hasSize(2);
        assertThat(setv.arguments().get(1)).isInstanceOf(VectorLiteralNode.class);

        VectorLiteralNode vector = (VectorLiteralNode) setv.arguments().get(1);
        assertThat(vector.values()).hasSize(2);
        assertThat(vector.values().get(0)).isEqualTo(10);
        assertThat(vector.values().get(1)).isEqualTo(-20);
    }

    /**
     * Verifies that the parser correctly handles an exported label (e.g., "EXPORT L1: NOP").
     * The exported flag should be true and the statement should be parsed correctly after the label.
     * This is a unit test for the parser.
     */
    @Test
    @Tag("unit")
    void testParserExportedLabel() {
        // Arrange
        String source = "EXPORT .LABEL L1 NOP";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(2);
        assertThat(ast.get(0)).isInstanceOf(LabelNode.class);

        LabelNode labelNode = (LabelNode) ast.get(0);
        assertThat(labelNode.name()).isEqualTo("L1");
        assertThat(labelNode.exported()).isTrue();
        assertThat(ast.get(1)).isInstanceOf(InstructionNode.class);

        InstructionNode nopNode = (InstructionNode) ast.get(1);
        assertThat(nopNode.opcode()).isEqualTo("NOP");
    }

    /**
     * Verifies that a non-exported label has the exported flag set to false.
     * This is a unit test for the parser.
     */
    @Test
    @Tag("unit")
    void testParserNonExportedLabel() {
        // Arrange
        String source = ".LABEL L1 NOP";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        LabelNode labelNode = (LabelNode) ast.get(0);
        assertThat(labelNode.exported()).isFalse();
    }

    /**
     * Verifies that an exported label followed by a statement on the next line
     * is parsed as the label followed by that statement.
     * This is a unit test for the parser.
     */
    @Test
    @Tag("unit")
    void testParserExportedLabelWithStatementOnNextLine() {
        // Arrange
        String source = "EXPORT .LABEL L1\nNOP";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert - the NOP follows the label as a node of its own
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(2);

        LabelNode labelNode = (LabelNode) ast.get(0);
        assertThat(labelNode.name()).isEqualTo("L1");
        assertThat(labelNode.exported()).isTrue();
        assertThat(ast.get(1)).isInstanceOf(InstructionNode.class);

        InstructionNode nopNode = (InstructionNode) ast.get(1);
        assertThat(nopNode.opcode()).isEqualTo("NOP");
    }

    /**
     * Verifies that EXPORT before an instruction is reported, because an instruction defines no
     * name that could be exported, and the instruction is parsed all the same.
     * This is a unit test for the parser.
     */
    @Test
    @Tag("unit")
    void testParserExportBeforeAnInstructionIsReported() {
        // Arrange
        String source = "EXPORT NOP";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = new Parser(new Lexer(source, diagnostics, TestLexers.symbols()).scanTokens(), diagnostics, registry());

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.summary()).contains("EXPORT is not supported before 'NOP'.");
        assertThat(ast).singleElement().isInstanceOf(InstructionNode.class);
    }

    /**
     * Verifies that the parser correctly handles an exported constant (e.g., "EXPORT .CONST X 42").
     * The exported flag should be true.
     */
    @Test
    @Tag("unit")
    void testParserExportedConst() {
        // Arrange
        String source = "EXPORT .CONST MAX_ENERGY 42";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        assertThat(ast.get(0)).isInstanceOf(ConstNode.class);

        ConstNode constNode = (ConstNode) ast.get(0);
        assertThat(constNode.name()).isEqualTo("MAX_ENERGY");
        assertThat(constNode.exported()).isTrue();
        assertThat(constNode.value()).isInstanceOf(NumberLiteralNode.class);
        assertThat(((NumberLiteralNode) constNode.value()).value()).isEqualTo(42);
    }

    /**
     * Verifies that a non-exported constant has the exported flag set to false.
     */
    @Test
    @Tag("unit")
    void testParserNonExportedConst() {
        // Arrange
        String source = ".CONST MAX_ENERGY 42";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        assertThat(ast.get(0)).isInstanceOf(ConstNode.class);

        ConstNode constNode = (ConstNode) ast.get(0);
        assertThat(constNode.name()).isEqualTo("MAX_ENERGY");
        assertThat(constNode.exported()).isFalse();
    }

    /**
     * A handler that fails with an exception it did not report is a defect in the compiler,
     * not a mistake in the program: the parser must not recover from it as if it were a syntax
     * error, because that would drop the statement from the program without a word.
     */
    @Test
    @Tag("unit")
    void aDefectInAHandlerLeavesTheParserInsteadOfDroppingTheStatement() {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<Token> tokens = new Lexer(".BROKEN\nNOP\n", diagnostics, TestLexers.symbols()).scanTokens();
        ParserStatementRegistry reg = registry();
        reg.register(".BROKEN", context -> { throw new NullPointerException("defect in the handler"); });
        Parser parser = new Parser(tokens, diagnostics, reg);

        assertThatThrownBy(parser::parse)
                .isInstanceOf(NullPointerException.class)
                .hasMessage("defect in the handler");
    }

    private static ParserStatementRegistry registry() {
        ParserStatementRegistry reg = new ParserStatementRegistry();
        reg.register(".CONST", new ConstDirectiveHandler());
        reg.register(".LABEL", new org.evochora.compiler.features.label.LabelDirectiveHandler());
        reg.registerDefault(new org.evochora.compiler.features.instruction.InstructionParsingHandler());
        return reg;
    }
}