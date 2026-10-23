package org.evochora.compiler.expression;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.expression.Kind;
import org.evochora.compiler.model.expression.LiteralExpression;
import org.evochora.compiler.model.expression.NameExpression;
import org.evochora.compiler.model.expression.OperationExpression;
import org.evochora.compiler.model.expression.Value;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.evochora.compiler.expression.ExpressionFixture.shape;

/**
 * Unit tests for {@link Evaluator}: values, the overloads chosen by the kinds of the operands,
 * the smaller tree for a later phase, and the errors, with the operations of the standard
 * features.
 */
@Tag("unit")
class EvaluatorTest {

    private static ExpressionFixture fixture;

    /** A constant of {@code ENERGY:50}. */
    private static final Map<String, NameAnswer> UNIT = Map.of("UNIT", new NameAnswer.Value(new Value.Typed("ENERGY", 50)));

    @BeforeAll
    static void init() {
        fixture = ExpressionFixture.withMin();
    }

    private static Value value(String source, Map<String, NameAnswer> names) {
        EvaluationResult result = fixture.evaluate(source, names);
        assertThat(result).isInstanceOf(EvaluationResult.Value.class);
        return ((EvaluationResult.Value) result).value();
    }

    private static Value value(String source) {
        return value(source, Map.of());
    }

    private static String error(String source, Map<String, NameAnswer> names) {
        EvaluationResult result = fixture.evaluate(source, names);
        assertThat(result).isInstanceOf(EvaluationResult.Error.class);
        return ((EvaluationResult.Error) result).reason();
    }

    private static String error(String source) {
        return error(source, Map.of());
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = ';', quoteCharacter = '`', value = {
            "1 + 2 * 3; 7",
            "(1 + 2) * 3; 9",
            "7 / 2; 3",
            "-7 / 2; -3",
            "7 MOD 3; 1",
            "-7 MOD 3; -1",
            "- -5; 5",
            "6 AND 3; 2",
            "6 OR 3; 7",
            "6 XOR 3; 5",
            "1 SHL 4; 16",
            "-16 SHR 2; -4",
            "NOT 0; -1",
            "MIN(3, 4); 3",
            "(1|2)[1]; 2",
    })
    void computesNumbers(String source, long expected) {
        assertThat(value(source)).isEqualTo(new Value.Number(expected));
    }

    @Test
    void aTypedValueKeepsItsTypeWithANumber() {
        assertThat(value("UNIT * 2", UNIT)).isEqualTo(new Value.Typed("ENERGY", 100));
        assertThat(value("-UNIT", UNIT)).isEqualTo(new Value.Typed("ENERGY", -50));
        assertThat(value("UNIT + UNIT", UNIT)).isEqualTo(new Value.Typed("ENERGY", 100));
    }

    @Test
    void twoTypedValuesCombineOnlyWithEqualTypes() {
        assertThat(error("UNIT + DATA:1", UNIT)).isEqualTo("Type mismatch: ENERGY and DATA.");
    }

    @Test
    void aTypedValueKeepsItsTypeWithANumberInFront() {
        assertThat(value("2 * UNIT", UNIT)).isEqualTo(new Value.Typed("ENERGY", 100));
        assertThat(value("1 - UNIT", UNIT)).isEqualTo(new Value.Typed("ENERGY", -49));
        assertThat(value("100 / UNIT", UNIT)).isEqualTo(new Value.Typed("ENERGY", 2));
        assertThat(value("120 MOD UNIT", UNIT)).isEqualTo(new Value.Typed("ENERGY", 20));
        assertThat(value("1 + UNIT", UNIT)).isEqualTo(new Value.Typed("ENERGY", 51));
    }

    @Test
    void divisionByZeroAndOverflowAreErrors() {
        assertThat(error("1 / 0")).isEqualTo("Division by zero.");
        assertThat(error("1 MOD 0")).isEqualTo("Division by zero.");
        assertThat(error("(1|2) / 0")).isEqualTo("Division by zero.");
        assertThat(error("9223372036854775807 + 1")).isEqualTo("Integer overflow.");
        assertThat(error("4611686018427387904 * 2")).isEqualTo("Integer overflow.");
        assertThat(error("(9223372036854775807|0) * 2")).isEqualTo("Integer overflow.");
    }

    @Test
    void anErrorStandsAtTheNodeThatFailed() {
        EvaluationResult result = fixture.evaluate("1 + 4 / 0");
        assertThat(((EvaluationResult.Error) result).at().columnNumber()).isEqualTo(5);
    }

    @Test
    void computesVectors() {
        assertThat(value("(1|2) + (3|4)")).isEqualTo(new Value.Vector(List.of(4L, 6L)));
        assertThat(value("(1|2) - (3|4)")).isEqualTo(new Value.Vector(List.of(-2L, -2L)));
        assertThat(value("(1|2) * 3")).isEqualTo(new Value.Vector(List.of(3L, 6L)));
        assertThat(value("3 * (1|2)")).isEqualTo(new Value.Vector(List.of(3L, 6L)));
        assertThat(value("(4|-6) / 2")).isEqualTo(new Value.Vector(List.of(2L, -3L)));
        assertThat(value("-(1|-2)")).isEqualTo(new Value.Vector(List.of(-1L, 2L)));
        assertThat(value("WIDTH-1|HEIGHT-1", Map.of("WIDTH", new NameAnswer.Value(new Value.Number(100)),
                "HEIGHT", new NameAnswer.Value(new Value.Number(70))))).isEqualTo(new Value.Vector(List.of(99L, 69L)));
    }

    @Test
    void reportsWhatAVectorCannotDo() {
        assertThat(error("(1|2) * (3|4)")).isEqualTo("Invalid operands for '*': vector and vector.");
        assertThat(error("(1|2) + (1|2|3)")).isEqualTo("Expected a vector with 2 components.");
        assertThat(error("(1|2)[3]")).isEqualTo("Index 3 out of bounds for a vector of length 2.");
        assertThat(error("(1|2)[-1]")).isEqualTo("Index -1 out of bounds for a vector of length 2.");
        assertThat(error("(1|2) + 1")).isEqualTo("Invalid operands for '+': vector and number.");
    }

    @Test
    void aChainTakesNumbersOnly() {
        Map<String, NameAnswer> nw = Map.of("NW", new NameAnswer.Value(new Value.Vector(List.of(1L, 2L))));
        assertThat(error("5|NW", nw)).isEqualTo("Invalid vector component: 'NW' is a vector.");
        assertThat(error("NW|5", nw)).isEqualTo("Invalid vector component: 'NW' is a vector.");
    }

    @Test
    void anOverloadIsChosenByTheKindsOfTheOperands() {
        // The same '-' computes a number, a vector and a typed value, by what it is given
        assertThat(value("3 - 1")).isEqualTo(new Value.Number(2));
        assertThat(value("(3|3) - (1|1)")).isEqualTo(new Value.Vector(List.of(2L, 2L)));
        assertThat(value("UNIT - 1", UNIT)).isEqualTo(new Value.Typed("ENERGY", 49));
    }

    @Test
    void anOverloadIsChosenForAnOperandWithoutAValue() {
        // A label is a position only the layout knows; minus a vector it stays a position
        Map<String, NameAnswer> label = Map.of("NW", new NameAnswer.Later(Kind.POSITION, "LIB.NW"));
        EvaluationResult result = fixture.evaluate("NW - (1|1)", label);
        assertThat(result).isInstanceOf(EvaluationResult.Partial.class);
        EvaluationResult.Partial partial = (EvaluationResult.Partial) result;
        assertThat(partial.kind()).isEqualTo(Kind.POSITION);
        assertThat(shape(partial.expression())).isEqualTo("(- LIB.NW 1|1)");

        assertThat(((EvaluationResult.Partial) fixture.evaluate("NW - NW", label)).kind()).isEqualTo(Kind.VECTOR);
        assertThat(error("NW - 1", label)).isEqualTo("Invalid operands for '-': position and number.");
        assertThat(error("NW * 2", label)).isEqualTo("Invalid operands for '*': position and number.");
        assertThat(error("-NW", label)).isEqualTo("Invalid operand for '-': position.");
    }

    @Test
    void aPartialTreeHoldsTheComputedPartsAsLiterals() {
        EvaluationResult result = fixture.evaluate("(@ + (2 * 3|1))[0] + 4 * 2");
        assertThat(result).isInstanceOf(EvaluationResult.Partial.class);
        EvaluationResult.Partial partial = (EvaluationResult.Partial) result;
        assertThat(partial.kind()).isEqualTo(Kind.NUMBER);
        assertThat(shape(partial.expression())).isEqualTo("(+ ([] (+ @ 6|1) 0) 8)");
        OperationExpression sum = (OperationExpression) partial.expression();
        assertThat(sum.operands().get(1)).isInstanceOf(LiteralExpression.class);
        assertThat(sum.operands().get(1).sourceInfo().columnNumber()).isEqualTo(22);
    }

    @Test
    void aNameALaterPhaseKnowsStaysUnderItsQualifiedName() {
        EvaluationResult result = fixture.evaluate("TARGET", Map.of("TARGET", new NameAnswer.Later(Kind.POSITION, "LIB.TARGET")));
        assertThat(result).isInstanceOf(EvaluationResult.Partial.class);
        EvaluationResult.Partial partial = (EvaluationResult.Partial) result;
        assertThat(partial.expression()).isInstanceOf(NameExpression.class);
        assertThat(((NameExpression) partial.expression()).name()).isEqualTo("LIB.TARGET");
        assertThat(partial.kind()).isEqualTo(Kind.POSITION);
    }

    @Test
    void aReportedNameMakesTheWholeComputationReported() {
        assertThat(fixture.evaluate("1 + (X * 2)", Map.of("X", new NameAnswer.Reported())))
                .isInstanceOf(EvaluationResult.Reported.class);
    }

    @Test
    void aNameThatCannotBeUsedIsAnErrorAtTheName() {
        EvaluationResult result = fixture.evaluate("1 + FOO");
        assertThat(result).isEqualTo(new EvaluationResult.Error("'FOO' is not defined.",
                ((OperationExpression) fixture.expression("1 + FOO")).operands().get(1).sourceInfo()));
    }

    @Test
    void anOperationThatNeedsTheLayoutAnswersNotYetInTheFrontend() {
        EvaluationResult result = fixture.evaluate("POSITION(1|2)");
        assertThat(result).isInstanceOf(EvaluationResult.Partial.class);
        assertThat(((EvaluationResult.Partial) result).kind()).isEqualTo(Kind.POSITION);
        assertThat(shape(((EvaluationResult.Partial) result).expression())).isEqualTo("(POSITION 1|2)");
    }

    @Test
    void anOperationThatNeedsTheLayoutComputesWhereTheLayoutIsKnown() {
        IEvaluationContext layout = IEvaluationContext.at(new Value.Position(List.of(7L, 7L)), new Value.Position(List.of(10L, 20L)));
        EvaluationResult result = fixture.evaluate("POSITION(1|2)[1]", Map.of(), layout);
        assertThat(result).isEqualTo(new EvaluationResult.Value(new Value.Number(2)));
    }

    @Test
    void aLiteralIsItsValue() {
        Evaluator evaluator = new Evaluator(fixture.operations());
        SourceInfo at = new SourceInfo("main.evo", 1, 1, "", 0);
        LiteralExpression literal = new LiteralExpression(new Value.Typed("DATA", 3), at, at.plusColumns(6));
        assertThat(evaluator.evaluate(literal, name -> new NameAnswer.Reported(), IEvaluationContext.none()))
                .isEqualTo(new EvaluationResult.Value(new Value.Typed("DATA", 3)));
    }

    @Test
    void anOperationTheRegistryDoesNotKnowIsAnError() {
        Evaluator evaluator = new Evaluator(new OperationRegistry());
        OperationExpression unknown = new OperationExpression("+", false, List.of(
                new LiteralExpression(new Value.Number(1), null, null), new LiteralExpression(new Value.Number(2), null, null)), null, null);
        assertThat(evaluator.evaluate(unknown, name -> new NameAnswer.Reported(), IEvaluationContext.none()))
                .isEqualTo(new EvaluationResult.Error("Unknown operation '+'.", null));
    }
}
