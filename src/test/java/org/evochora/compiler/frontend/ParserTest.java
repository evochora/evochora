package org.evochora.compiler.frontend;

import org.evochora.compiler.model.ast.ExpressionNode;
import org.evochora.compiler.model.ast.LiteralNode;
import org.evochora.compiler.model.ast.OperandNode;
import org.evochora.compiler.model.expression.NameExpression;
import org.evochora.compiler.model.expression.OperationExpression;
import org.evochora.compiler.TestRegistries;
import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;
import org.evochora.compiler.diagnostics.Diagnostic;
import org.evochora.compiler.model.expression.Value;
import org.evochora.compiler.TestLexers;
import org.evochora.compiler.frontend.lexer.Tokenizer;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ParserStatementRegistry;
import org.evochora.compiler.features.constdir.ConstDirectiveHandler;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.InstructionNode;
import org.evochora.compiler.model.ast.RegisterNode;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.features.constdir.ConstNode;
import org.evochora.compiler.features.label.LabelNode;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        Tokenizer lexer = TestLexers.tokenizer(source, diagnostics);
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
        assertThat(setiNode.arguments().get(1)).isInstanceOf(LiteralNode.class);

        RegisterNode regArg = (RegisterNode) setiNode.arguments().get(0);
        assertThat(regArg.name()).isEqualTo("%DR0");

        LiteralNode numArg = (LiteralNode) setiNode.arguments().get(1);
        assertThat(numArg.value()).isEqualTo(new Value.Number(42));
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
        Tokenizer lexer = TestLexers.tokenizer(source, diagnostics);
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
     * a {@link LiteralNode} holding a vector with the correct integer components.
     * This is a unit test for the parser.
     */
    @Test
    @Tag("unit")
    void testParserVectorLiteral() {
        // Arrange
        String source = "SETV %DR0 10|-20";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Tokenizer lexer = TestLexers.tokenizer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry()); // KORREKTUR

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        InstructionNode setv = (InstructionNode) ast.get(0);
        assertThat(setv.arguments()).hasSize(2);
        assertThat(setv.arguments().get(1)).isInstanceOf(LiteralNode.class);

        Value.Vector vector = (Value.Vector) ((LiteralNode) setv.arguments().get(1)).value();
        assertThat(vector.components()).containsExactly(10L, -20L);
    }

    /**
     * Verifies that a minus before a number makes it negative: for a bare number, for every
     * component of a vector, including the first, and for the value of a typed literal.
     * This is a unit test for the parser.
     */
    @Test
    @Tag("unit")
    void testParserSignedNumbers() {
        // Arrange
        String source = "SETI %DR0 -7\nSETV %DR0 -1|0|-3\nSETI %DR0 DATA:-1";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Tokenizer lexer = TestLexers.tokenizer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(3);
        assertThat(((LiteralNode) ((InstructionNode) ast.get(0)).arguments().get(1)).value())
                .isEqualTo(new Value.Number(-7));
        assertThat(((LiteralNode) ((InstructionNode) ast.get(1)).arguments().get(1)).value())
                .isEqualTo(new Value.Vector(List.of(-1L, 0L, -3L)));
        assertThat(((LiteralNode) ((InstructionNode) ast.get(2)).arguments().get(1)).value())
                .isEqualTo(new Value.Typed("DATA", -1));
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
        Tokenizer lexer = TestLexers.tokenizer(source, diagnostics);
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
        Tokenizer lexer = TestLexers.tokenizer(source, diagnostics);
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
        Tokenizer lexer = TestLexers.tokenizer(source, diagnostics);
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
        Parser parser = new Parser(TestLexers.tokenizer(source, diagnostics).scanTokens(), diagnostics, registry());

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
        Tokenizer lexer = TestLexers.tokenizer(source, diagnostics);
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
        assertThat(constNode.value()).isInstanceOf(LiteralNode.class);
        assertThat(((LiteralNode) constNode.value()).value()).isEqualTo(new Value.Number(42));
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
        Tokenizer lexer = TestLexers.tokenizer(source, diagnostics);
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
        List<Token> tokens = TestLexers.tokenizer(".BROKEN\nNOP\n", diagnostics).scanTokens();
        ParserStatementRegistry reg = registry();
        reg.register(".BROKEN", context -> { throw new NullPointerException("defect in the handler"); });
        Parser parser = new Parser(tokens, diagnostics, reg);

        assertThatThrownBy(parser::parse)
                .isInstanceOf(NullPointerException.class)
                .hasMessage("defect in the handler");
    }

    /**
     * A parenthesis in operand position is a computation: the parser cuts it to its matching
     * parenthesis and carries the tree with the positions of its first and last token.
     */
    @Test
    @Tag("unit")
    void aParenthesisIsAComputation() {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        InstructionNode seti = (InstructionNode) parseWithOperations("SETI %DR0 (WIDTH - 1)", diagnostics).getFirst();

        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(seti.arguments()).hasSize(2);
        ExpressionNode computation = (ExpressionNode) seti.arguments().get(1);
        assertThat(computation.expression().text()).isEqualTo("(WIDTH-1)");
        assertThat(computation.sourceInfo().columnNumber()).isEqualTo(11);
        assertThat(computation.end().columnNumber()).isEqualTo(21);
    }

    /**
     * A function name directly followed by its parenthesis is a call, read with its arguments.
     */
    @Test
    @Tag("unit")
    void aCallIsAComputation() {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        InstructionNode seti = (InstructionNode) parseWithOperations("SETI %DR0 NUMBER(DATA:5)", diagnostics).getFirst();

        assertThat(diagnostics.hasErrors()).isFalse();
        ExpressionNode computation = (ExpressionNode) seti.arguments().get(1);
        assertThat(computation.expression()).isInstanceOf(OperationExpression.class);
        assertThat(((OperationExpression) computation.expression()).operation()).isEqualTo("NUMBER");
        assertThat(computation.expression().text()).isEqualTo("NUMBER(DATA:5)");
    }

    /**
     * A name followed by a bracket is an index, and indexes chain.
     */
    @Test
    @Tag("unit")
    void anIndexIsAComputationAndIndexesChain() {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = parseWithOperations("SETV %DR0 V[0]\nSETV %DR1 V[0][1]", diagnostics);

        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(((ExpressionNode) ((InstructionNode) ast.get(0)).arguments().get(1)).expression().text()).isEqualTo("V[0]");
        assertThat(((ExpressionNode) ((InstructionNode) ast.get(1)).arguments().get(1)).expression().text()).isEqualTo("V[0][1]");
    }

    /**
     * A typed literal whose value is a computation is one computation, the typing of the type
     * and the value.
     */
    @Test
    @Tag("unit")
    void aTypedLiteralWithAComputedValueIsAComputation() {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        InstructionNode seti = (InstructionNode) parseWithOperations("SETI %DR0 DATA:(W-1)", diagnostics).getFirst();

        assertThat(diagnostics.hasErrors()).isFalse();
        ExpressionNode computation = (ExpressionNode) seti.arguments().get(1);
        assertThat(((OperationExpression) computation.expression()).operation()).isEqualTo(":");
        assertThat(computation.expression().text()).isEqualTo("DATA:(W-1)");
        assertThat(computation.sourceInfo().columnNumber()).isEqualTo(11);
    }

    /**
     * The cursor alone is a computation, a name the layout knows; a typed literal with a number
     * stays a literal.
     */
    @Test
    @Tag("unit")
    void theCursorIsAComputationAndATypedNumberStaysALiteral() {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = parseWithOperations("SETV %DR0 @\nSETI %DR1 DATA:-5", diagnostics);

        assertThat(diagnostics.hasErrors()).isFalse();
        ExpressionNode cursor = (ExpressionNode) ((InstructionNode) ast.get(0)).arguments().get(1);
        assertThat(cursor.expression()).isEqualTo(new NameExpression("@", cursor.sourceInfo()));
        assertThat(((InstructionNode) ast.get(1)).arguments().get(1)).isInstanceOfSatisfying(LiteralNode.class,
                literal -> assertThat(literal.value()).isEqualTo(new Value.Typed("DATA", -5)));
    }

    /**
     * A computation the expression parser cannot read is reported at the token at fault, and the
     * operand is left out.
     */
    @Test
    @Tag("unit")
    void aComputationThatCannotBeReadIsReportedAtTheTokenAtFault() {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        parseWithOperations("NOP\nSETI %DR0 (A +)", diagnostics);

        assertThat(diagnostics.getDiagnostics()).singleElement().satisfies(d -> {
            assertThat(d.message()).isEqualTo("Expected an expression after '+'.");
            assertThat(d.lineNumber()).isEqualTo(2);
        });
    }

    /**
     * A parser built without operations reads a parenthesis, and an operator in it is unexpected.
     */
    @Test
    @Tag("unit")
    void aParserWithoutOperationsReportsAnOperatorAsUnexpected() {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        new Parser(TestLexers.tokenizer("SETI %DR0 (A+1)", diagnostics).scanTokens(), diagnostics, registry()).parse();

        assertThat(diagnostics.getDiagnostics()).extracting(Diagnostic::message).containsExactly("Unexpected '+'.");
    }

    /**
     * The reporting form of reading an operand apart from the stream reports what keeps the
     * tokens from being one operand; the silent form reports nothing.
     */
    @Test
    @Tag("unit")
    void theReportingOperandReaderReportsWhatTheSilentOneKeepsToItself() {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = new Parser(List.of(), diagnostics, registry(), null, Map.of(),
                TestRegistries.operations(), new RuntimeInstructionSetAdapter());
        DiagnosticsEngine lexing = new DiagnosticsEngine();
        List<Token> computation = withoutEnd(TestLexers.tokenizer("(W - 1)", lexing).scanTokens());
        List<Token> twoOperands = withoutEnd(TestLexers.tokenizer("1 2", lexing).scanTokens());
        List<Token> broken = withoutEnd(TestLexers.tokenizer("(W -)", lexing).scanTokens());

        Optional<OperandNode> read = parser.readOperand(computation);
        assertThat(read).get().isInstanceOf(ExpressionNode.class);
        assertThat(parser.operandOf(TestLexers.tokenizer("1 2", lexing).scanTokens())).isEmpty();
        assertThat(diagnostics.hasErrors()).isFalse();

        assertThat(parser.readOperand(twoOperands)).isEmpty();
        assertThat(parser.readOperand(broken)).isEmpty();
        assertThat(diagnostics.getDiagnostics()).extracting(Diagnostic::message)
                .containsExactly("Unexpected '2'.", "Expected an expression after '-'.");
    }

    private static List<Token> withoutEnd(List<Token> tokens) {
        return tokens.stream().filter(t -> t.type() != org.evochora.compiler.model.token.TokenType.END_OF_FILE
                && t.type() != org.evochora.compiler.model.token.TokenType.NEWLINE).toList();
    }

    private static List<AstNode> parseWithOperations(String source, DiagnosticsEngine diagnostics) {
        List<Token> tokens = TestLexers.tokenizer(source, diagnostics).scanTokens();
        return new Parser(tokens, diagnostics, registry(), null, Map.of(),
                TestRegistries.operations(), new RuntimeInstructionSetAdapter())
                .parse().stream().filter(Objects::nonNull).toList();
    }

    private static ParserStatementRegistry registry() {
        ParserStatementRegistry reg = new ParserStatementRegistry();
        reg.register(".CONST", new ConstDirectiveHandler());
        reg.register(".LABEL", new org.evochora.compiler.features.label.LabelDirectiveHandler());
        reg.registerDefault(new org.evochora.compiler.features.instruction.InstructionParsingHandler());
        return reg;
    }
}