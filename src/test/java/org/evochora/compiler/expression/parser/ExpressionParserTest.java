package org.evochora.compiler.expression.parser;

import org.evochora.compiler.expression.ExpressionFixture;
import org.evochora.compiler.model.expression.Expression;
import org.evochora.compiler.model.expression.OperationExpression;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.evochora.compiler.expression.ExpressionFixture.shape;

/**
 * Unit tests for {@link ExpressionParser}: the forms of a computation, the precedence scale and
 * the messages for what is no computation.
 */
@Tag("unit")
class ExpressionParserTest {

    private static ExpressionFixture fixture;

    @BeforeAll
    static void init() {
        fixture = ExpressionFixture.withMin();
    }

    private static String parsed(String source) {
        return shape(fixture.expression(source));
    }

    private static ParseResult.Failed failed(String source) {
        ParseResult result = fixture.parse(source);
        assertThat(result).isInstanceOf(ParseResult.Failed.class);
        return (ParseResult.Failed) result;
    }

    @ParameterizedTest(name = "{0} reads as {1}")
    @CsvSource(delimiter = ';', quoteCharacter = '`', value = {
            // The precedence table
            "(WIDTH-1|HEIGHT-1); (| (- WIDTH 1) (- HEIGHT 1))",
            "(0|@+2); (| 0 (+ @ 2))",
            "(BODY_NW - 1|1); (| (- BODY_NW 1) 1)",
            "-DATA:5; (- (: type DATA 5))",
            "DATA:-5; (: type DATA (- 5))",
            "NOT(A); (NOT A)",
            "MIN(A, 4); (MIN A 4)",
            "V[0]; ([] V 0)",
            "A*-1; (* A (- 1))",
            // Levels and directions
            "1 + 2 * 3; (+ 1 (* 2 3))",
            "10 - 2 - 3; (- (- 10 2) 3)",
            "(1 + 2) * 3; (* (+ 1 2) 3)",
            "1|2|3; (| 1 2 3)",
            "A MOD 2 + 1; (+ (MOD A 2) 1)",
            "A SHL 2 SHL 1; (SHL (SHL A 2) 1)",
            "(A SHL 2) + 1; (+ (SHL A 2) 1)",
            "V[0] AND 1; (AND ([] V 0) 1)",
            "-A SHL 1; (SHL (- A) 1)",
            "NOT 5; (NOT 5)",
            "--5; (- (- 5))",
            // Atoms and calls
            "42; 42",
            "@; @",
            "LIB.WIDTH; LIB.WIDTH",
            "FOO:5; (: FOO 5)",
            "TYPE(UNIT):7; (: (TYPE UNIT) 7)",
            "DATA:(WIDTH-1); (: type DATA (- WIDTH 1))",
            "DATA:V[0]; (: type DATA ([] V 0))",
            "MIN(A, 4)[0]; ([] (MIN A 4) 0)",
            "POSITION(1|2); (POSITION (| 1 2))",
            "V[I + 1]; ([] V (+ I 1))",
    })
    void readsTheForms(String source, String expected) {
        assertThat(parsed(source)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} reports {1}")
    @CsvSource(delimiter = ';', quoteCharacter = '`', value = {
            "A SHL 2 + 1; 'SHL' and '+' need parentheses.",
            "A + 1 SHL 2; '+' and 'SHL' need parentheses.",
            "A SHL 2 AND 1; 'SHL' and 'AND' need parentheses.",
            "A AND 1|2; 'AND' and '|' need parentheses.",
            "%DR0 + 1; Register '%DR0' cannot be used in an expression.",
            "MINN(1); Unknown function 'MINN'.",
            "-; Expected an expression after '-'.",
            "1 +; Expected an expression after '+'.",
            "(1 + ); Expected an expression after '+'.",
            "MIN(1, ); Expected an expression after ','.",
            "V[]; Expected an expression after '['.",
            "1 ^ 2; Unexpected '^'.",
            "(1 + 2; Expected ')'.",
            "(1 2); Unexpected '2'.",
            "AND 1; Unexpected 'AND'.",
            // A name and a parenthesis are two operands, not a call
            "MIN (1); Unexpected '('.",
            "\"text\"; Unexpected '\"text\"'.",
    })
    void reportsWhatIsNoComputation(String source, String message) {
        assertThat(failed(source).message()).isEqualTo(message);
    }

    @Test
    void anEmptyListIsNoComputation() {
        ParseResult.Failed failed = failed("");
        assertThat(failed.message()).isEqualTo("Expected an expression.");
    }

    @Test
    void anErrorStandsAtTheTokenAtFault() {
        ParseResult.Failed failed = failed("1 + %DR0");
        assertThat(failed.at().columnNumber()).isEqualTo(5);
    }

    @Test
    void anOperationStandsAtItsFirstToken() {
        Expression sum = fixture.expression("  A + 1");
        assertThat(sum.sourceInfo().columnNumber()).isEqualTo(3);
        Expression sign = fixture.expression("1 * -A");
        assertThat(((OperationExpression) sign).operands().get(1).sourceInfo().columnNumber()).isEqualTo(5);
    }

    @ParameterizedTest(name = "{0} ends at column {1}")
    @CsvSource(delimiter = ';', value = {
            "A + 1; 6",
            "(A + B); 8",
            "((5)); 6",
            "MIN(A, 4); 10",
            "V[0]; 5",
            "-(A); 5",
            "1|2|3; 6",
    })
    void aNodeEndsAfterTheLastTokenReadForIt(String source, int column) {
        assertThat(fixture.expression(source).end().columnNumber()).isEqualTo(column);
    }

    @Test
    void anOperandEndsAfterItsOwnLastTokenItsParenthesisIncluded() {
        OperationExpression product = (OperationExpression) fixture.expression("(A + B) * 2");
        assertThat(product.end().columnNumber()).isEqualTo(12);
        assertThat(product.operands().get(0).sourceInfo().columnNumber()).isEqualTo(2);
        assertThat(product.operands().get(0).end().columnNumber()).isEqualTo(8);
        OperationExpression typing = (OperationExpression) fixture.expression("DATA:(W - 1)");
        assertThat(typing.operands().get(0).end().columnNumber()).isEqualTo(5);
        assertThat(typing.operands().get(1).end().columnNumber()).isEqualTo(13);
    }

    @Test
    void aWordOperatorMayArriveAsAnOpcode() {
        // NOT is a mnemonic of the instruction set as well
        assertThat(fixture.tokens("NOT 5").getFirst().type())
                .isEqualTo(org.evochora.compiler.model.token.TokenType.OPCODE);
        assertThat(parsed("NOT 5")).isEqualTo("(NOT 5)");
    }

    @Test
    void theParserFixesNoOperatorTheRegistryDoesNotKnow() {
        ExpressionParser bare = new ExpressionParser(new org.evochora.compiler.expression.OperationRegistry(),
                new org.evochora.compiler.isa.RuntimeInstructionSetAdapter());
        assertThat(bare.parse(fixture.tokens("1 + 2"))).isEqualTo(new ParseResult.Failed("Unexpected '+'.",
                fixture.tokens("1 + 2").get(1).source()));
        assertThat(bare.parse(fixture.tokens("V[0]"))).isInstanceOf(ParseResult.Failed.class);
    }
}
