package org.evochora.compiler.expression;

import org.evochora.compiler.model.expression.Kind;
import org.evochora.compiler.model.expression.Value;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link OperationRegistry}: keys, overloads, and the rules it holds the
 * registrations to.
 */
@Tag("unit")
class OperationRegistryTest {

    /** An operation that computes nothing, for registrations only. */
    private record Op(String name, Notation notation, Level level, List<Signature> signatures) implements IOperation {
        @Override
        public OperationResult compute(List<Value> operands, IEvaluationContext context) {
            return new OperationResult.Value(new Value.Number(name.length()));
        }
    }

    private static Op op(String name, Notation notation, Level level, Kind... parameters) {
        return new Op(name, notation, level, List.of(Signature.of(Kind.NUMBER, parameters)));
    }

    @Test
    void anOperationIsFoundByNameAndNotation() {
        OperationRegistry registry = new OperationRegistry();
        registry.register(op("-", Notation.INFIX, Level.ADDITIVE, Kind.NUMBER, Kind.NUMBER));
        registry.register(op("-", Notation.PREFIX, Level.PREFIX, Kind.NUMBER));
        registry.register(op("min", Notation.FUNCTION, Level.INDEX, Kind.NUMBER, Kind.NUMBER));

        assertThat(registry.levelOf("-", Notation.INFIX)).contains(Level.ADDITIVE);
        assertThat(registry.levelOf("-", Notation.PREFIX)).contains(Level.PREFIX);
        assertThat(registry.find("-", Notation.CHAIN)).isEmpty();
        assertThat(registry.find("Min", Notation.FUNCTION)).map(IOperation::name).contains("MIN");
    }

    @Test
    void operationsUnderOneKeyAreTheOverloadsOfOne() {
        OperationRegistry registry = new OperationRegistry();
        registry.register(op("+", Notation.INFIX, Level.ADDITIVE, Kind.NUMBER, Kind.NUMBER));
        registry.register(op("+", Notation.INFIX, Level.ADDITIVE, Kind.VECTOR, Kind.VECTOR));
        IOperation plus = registry.find("+", Notation.INFIX).orElseThrow();
        assertThat(plus.signatures()).hasSize(2);
    }

    @Test
    void aDuplicateSignatureIsRejected() {
        OperationRegistry registry = new OperationRegistry();
        registry.register(op("+", Notation.INFIX, Level.ADDITIVE, Kind.NUMBER, Kind.NUMBER));
        assertThatThrownBy(() -> registry.register(op("+", Notation.INFIX, Level.ADDITIVE, Kind.NUMBER, Kind.NUMBER)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Operation '+' as INFIX takes (number, number) twice.");
    }

    @Test
    void aSecondLevelForOneKeyIsRejected() {
        OperationRegistry registry = new OperationRegistry();
        registry.register(op("+", Notation.INFIX, Level.ADDITIVE, Kind.NUMBER, Kind.NUMBER));
        assertThatThrownBy(() -> registry.register(op("+", Notation.INFIX, Level.MULTIPLICATIVE, Kind.VECTOR, Kind.VECTOR)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Operation '+' as INFIX stands at level ADDITIVE and cannot also stand at level MULTIPLICATIVE.");
    }

    @Test
    void aWordIsAnOperatorOrAFunctionNeverBoth() {
        OperationRegistry registry = new OperationRegistry();
        registry.register(op("NOT", Notation.PREFIX, Level.PREFIX, Kind.NUMBER));
        assertThatThrownBy(() -> registry.register(op("NOT", Notation.FUNCTION, Level.INDEX, Kind.NUMBER)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Operation 'NOT' cannot be both a function and an operator.");

        registry.register(op("MIN", Notation.FUNCTION, Level.INDEX, Kind.NUMBER, Kind.NUMBER));
        assertThatThrownBy(() -> registry.register(op("MIN", Notation.INFIX, Level.ADDITIVE, Kind.NUMBER, Kind.NUMBER)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Operation 'MIN' cannot be both a function and an operator.");
    }

    @Test
    void anOperatorIsAnInfixOrAChainNeverBoth() {
        OperationRegistry registry = new OperationRegistry();
        registry.register(new Op("|", Notation.CHAIN, Level.CHAIN, List.of(new Signature(List.of(Kind.NUMBER), true, Kind.VECTOR))));
        assertThatThrownBy(() -> registry.register(op("|", Notation.INFIX, Level.ADDITIVE, Kind.NUMBER, Kind.NUMBER)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Operation '|' cannot be both an infix and a chain operator.");
    }

    @Test
    void theNotationHasToFitTheLevel() {
        OperationRegistry registry = new OperationRegistry();
        assertThatThrownBy(() -> registry.register(op("NEG", Notation.PREFIX, Level.ADDITIVE, Kind.NUMBER)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Operation 'NEG' as PREFIX cannot stand at level ADDITIVE.");
        assertThatThrownBy(() -> registry.register(op("#", Notation.INFIX, Level.INDEX, Kind.NUMBER, Kind.NUMBER)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register(op("MAX", Notation.FUNCTION, Level.ADDITIVE, Kind.NUMBER)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anOperationNamedLikeAPreprocessorWordIsRejected() {
        OperationRegistry registry = new OperationRegistry();
        registry.register(op("^", Notation.INFIX, Level.UNORDERED, Kind.NUMBER, Kind.NUMBER));
        assertThatThrownBy(() -> registry.validate(Set.of("^", ".MACRO")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Operation '^' is a preprocessor directive.");
    }

    @Test
    void theTypingColonMayShareAPreprocessorWord() {
        OperationRegistry registry = new OperationRegistry();
        registry.register(op(":", Notation.INFIX, Level.TYPING, Kind.TYPE, Kind.NUMBER));
        registry.register(op("macro", Notation.FUNCTION, Level.INDEX, Kind.NUMBER));
        registry.validate(Set.of(":", "MACRO2"));
        assertThatThrownBy(() -> registry.validate(Set.of("Macro")))
                .hasMessage("Operation 'MACRO' is a preprocessor directive.");
    }

    @Test
    void theReservedNamesAreTheOperationsAndTheCursor() {
        OperationRegistry registry = new OperationRegistry();
        registry.register(op("shl", Notation.INFIX, Level.UNORDERED, Kind.NUMBER, Kind.NUMBER));
        registry.register(op("MIN", Notation.FUNCTION, Level.INDEX, Kind.NUMBER, Kind.NUMBER));
        registry.register(op("-", Notation.INFIX, Level.ADDITIVE, Kind.NUMBER, Kind.NUMBER));
        registry.register(op("-", Notation.PREFIX, Level.PREFIX, Kind.NUMBER));
        assertThat(registry.reservedNames().names()).containsExactlyInAnyOrder("SHL", "MIN", "-", "@");
        assertThat(registry.reservedNames().description("shl")).contains("an operator");
        assertThat(registry.reservedNames().description("Min")).contains("a function");
        assertThat(registry.reservedNames().description("-")).contains("an operator");
        assertThat(registry.reservedNames().description("@")).isEmpty();
    }

    @Test
    void theStandardOperationsRegisterAndPassTheirChecks() {
        OperationRegistry registry = ExpressionFixture.standard().operations();
        assertThat(registry.reservedNames().names()).contains("+", "-", "*", "/", "MOD", "AND", "OR", "XOR", "SHL", "SHR", "NOT",
                "|", "[]", ":", "TYPE", "NUMBER", "POSITION", "@");
    }

    @Test
    void anOverloadComputesWithTheMemberWhoseSignatureFits() {
        OperationRegistry registry = ExpressionFixture.standard().operations();
        IOperation minus = registry.find("-", Notation.INFIX).orElseThrow();
        assertThat(minus.compute(List.of(new Value.Position(List.of(5L, 5L)), new Value.Position(List.of(1L, 2L))), IEvaluationContext.none()))
                .isEqualTo(new OperationResult.Value(new Value.Vector(List.of(4L, 3L))));
        assertThat(minus.compute(List.of(new Value.Number(1), new Value.Vector(List.of(1L))), IEvaluationContext.none()))
                .isEqualTo(new OperationResult.Error("Invalid operands for '-': number and vector."));
    }
}
