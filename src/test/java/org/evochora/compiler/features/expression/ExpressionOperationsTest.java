package org.evochora.compiler.features.expression;

import org.evochora.compiler.expression.EvaluationResult;
import org.evochora.compiler.expression.ExpressionFixture;
import org.evochora.compiler.expression.IEvaluationContext;
import org.evochora.compiler.expression.NameAnswer;
import org.evochora.compiler.expression.Notation;
import org.evochora.compiler.expression.OperationResult;
import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;
import org.evochora.compiler.model.expression.Kind;
import org.evochora.compiler.model.expression.Value;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the operation groups of the {@code expression} feature, for what the evaluator's
 * tests leave out: the typing operations, the positions and the bitwise edges.
 */
@Tag("unit")
class ExpressionOperationsTest {

    private static ExpressionFixture fixture;

    private static final Map<String, NameAnswer> NAMES = Map.of(
            "UNIT", new NameAnswer.Value(new Value.Typed("ENERGY", 50)),
            "P", new NameAnswer.Value(new Value.Position(List.of(10L, 20L))),
            "Q", new NameAnswer.Value(new Value.Position(List.of(4L, 5L))));

    /** The layout of a line whose origin is {@code 3|3}. */
    private static final IEvaluationContext LAYOUT = IEvaluationContext.at(new Value.Position(List.of(0L, 0L)), new Value.Position(List.of(3L, 3L)));

    @BeforeAll
    static void init() {
        fixture = ExpressionFixture.standard();
    }

    private static EvaluationResult evaluate(String source) {
        return fixture.evaluate(source, NAMES);
    }

    private static Value value(String source, IEvaluationContext context) {
        EvaluationResult result = fixture.evaluate(source, NAMES, context);
        assertThat(result).isInstanceOf(EvaluationResult.Value.class);
        return ((EvaluationResult.Value) result).value();
    }

    private static String error(String source, IEvaluationContext context) {
        EvaluationResult result = fixture.evaluate(source, NAMES, context);
        assertThat(result).isInstanceOf(EvaluationResult.Error.class);
        return ((EvaluationResult.Error) result).reason();
    }

    @Test
    void typingGivesANumberAMoleculeType() {
        assertThat(value("DATA:5", IEvaluationContext.none())).isEqualTo(new Value.Typed("DATA", 5));
        assertThat(value("DATA:-5", IEvaluationContext.none())).isEqualTo(new Value.Typed("DATA", -5));
        assertThat(value("-DATA:5", IEvaluationContext.none())).isEqualTo(new Value.Typed("DATA", -5));
        assertThat(value("TYPE(UNIT):7", IEvaluationContext.none())).isEqualTo(new Value.Typed("ENERGY", 7));
        assertThat(value("TYPE(UNIT)", IEvaluationContext.none())).isEqualTo(new Value.Type("ENERGY"));
        assertThat(value("NUMBER(UNIT) + 1", IEvaluationContext.none())).isEqualTo(new Value.Number(51));
    }

    @Test
    void typingTakesAMoleculeTypeTheInstructionSetKnows() {
        TypingOperations typing = new TypingOperations(new RuntimeInstructionSetAdapter());
        OperationResult result = typing.operations().stream().filter(op -> op.name().equals(":")).findFirst().orElseThrow()
                .compute(List.of(new Value.Type("FOO"), new Value.Number(5)), IEvaluationContext.none());
        assertThat(result).isEqualTo(new OperationResult.Error("'FOO' is not a molecule type."));
        assertThat(error("FOO:5", IEvaluationContext.none())).isEqualTo("'FOO' is not defined.");
        assertThat(error("UNIT:5", IEvaluationContext.none())).isEqualTo("Invalid operands for ':': typed and number.");
    }

    @Test
    void aTypeNeverBecomesACellNorTakesPartInArithmetic() {
        assertThat(error("TYPE(UNIT) + 1", IEvaluationContext.none())).isEqualTo("Invalid operands for '+': type and number.");
        assertThat(error("-TYPE(UNIT)", IEvaluationContext.none())).isEqualTo("Invalid operand for '-': type.");
    }

    @Test
    void positionsFollowTheRulesOfPlaces() {
        assertThat(value("P + (1|1)", IEvaluationContext.none())).isEqualTo(new Value.Position(List.of(11L, 21L)));
        assertThat(value("(1|1) + P", IEvaluationContext.none())).isEqualTo(new Value.Position(List.of(11L, 21L)));
        assertThat(value("P - (1|1)", IEvaluationContext.none())).isEqualTo(new Value.Position(List.of(9L, 19L)));
        assertThat(value("P - Q", IEvaluationContext.none())).isEqualTo(new Value.Vector(List.of(6L, 15L)));
        assertThat(error("P + Q", IEvaluationContext.none())).isEqualTo("Invalid operands for '+': position and position.");
        assertThat(error("(1|1) - P", IEvaluationContext.none())).isEqualTo("Invalid operands for '-': vector and position.");
        assertThat(error("P * 2", IEvaluationContext.none())).isEqualTo("Invalid operands for '*': position and number.");
        assertThat(error("P + (1|1|1)", IEvaluationContext.none())).isEqualTo("Expected a vector with 2 components.");
    }

    @Test
    void theComponentOfAPositionCountsFromTheOriginOfTheLine() {
        EvaluationResult frontend = evaluate("P[0]");
        assertThat(frontend).isInstanceOf(EvaluationResult.Partial.class);
        assertThat(((EvaluationResult.Partial) frontend).kind()).isEqualTo(Kind.NUMBER);
        assertThat(value("P[0]", LAYOUT)).isEqualTo(new Value.Number(7));
        assertThat(error("P[2]", IEvaluationContext.none())).isEqualTo("Index 2 out of bounds for a position of length 2.");
    }

    @Test
    void positionNamesAPlaceRelativeToTheOriginOfTheLine() {
        assertThat(value("POSITION(1|2)", LAYOUT)).isEqualTo(new Value.Position(List.of(4L, 5L)));
        assertThat(value("POSITION(1|2) - (1|2)", LAYOUT)).isEqualTo(new Value.Position(List.of(3L, 3L)));
        assertThat(error("POSITION(1|2|3)", LAYOUT)).isEqualTo("Expected a vector with 2 components.");
        assertThat(evaluate("POSITION(1|2)")).isInstanceOf(EvaluationResult.Partial.class);
    }

    @Test
    void aShiftCountBeyondTheWidthIsAnError() {
        assertThat(error("1 SHL 64", IEvaluationContext.none())).isEqualTo("Shift count 64 out of range (0 to 63).");
        assertThat(error("1 SHR -1", IEvaluationContext.none())).isEqualTo("Shift count -1 out of range (0 to 63).");
        assertThat(error("(1|2) AND 1", IEvaluationContext.none())).isEqualTo("Invalid operands for 'AND': vector and number.");
    }

    @Test
    void theFeatureRegistersEveryOperationOfTheGroups() {
        assertThat(fixture.operations().find("MOD", Notation.INFIX)).isPresent();
        assertThat(fixture.operations().find("-", Notation.PREFIX).orElseThrow().signatures()).hasSize(3);
        assertThat(fixture.operations().find("|", Notation.CHAIN)).isPresent();
        assertThat(fixture.operations().find("POSITION", Notation.FUNCTION)).isPresent();
    }
}
