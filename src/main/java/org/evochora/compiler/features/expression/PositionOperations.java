package org.evochora.compiler.features.expression;

import org.evochora.compiler.expression.IEvaluationContext;
import org.evochora.compiler.expression.IOperation;
import org.evochora.compiler.expression.IOperationGroup;
import org.evochora.compiler.expression.Level;
import org.evochora.compiler.expression.Notation;
import org.evochora.compiler.expression.OperationResult;
import org.evochora.compiler.expression.Signature;
import org.evochora.compiler.model.expression.OperationExpression;
import org.evochora.compiler.model.expression.Kind;
import org.evochora.compiler.model.expression.Value;

import java.util.List;
import java.util.Optional;

/**
 * The operations on positions, the places of the program: a position plus or minus a vector is a
 * position, a vector plus a position too; a position minus a position is the vector between them;
 * a position plus a position, a position times a number and the sign of a position are errors,
 * as no signature takes them.
 * <p>
 * Two operations read the origin the line counts its vectors from, and answer "not yet" where it
 * is not known: the component {@code P[i]}, which counts from that origin, and {@code POSITION(v)},
 * the place {@code v} relative to it.
 */
final class PositionOperations implements IOperationGroup {

    @Override
    public List<IOperation> operations() {
        return List.of(
                new DefinedOperation("+", Notation.INFIX, Level.ADDITIVE,
                        List.of(Signature.of(Kind.POSITION, Kind.POSITION, Kind.VECTOR),
                                Signature.of(Kind.POSITION, Kind.VECTOR, Kind.POSITION)),
                        (operands, context) -> operands.get(0) instanceof Value.Position position
                                ? Components.pairwise(position.components(), vector(operands.get(1)), Math::addExact, Value.Position::new)
                                : Components.pairwise(vector(operands.get(0)), position(operands.get(1)), Math::addExact, Value.Position::new)),
                new DefinedOperation("-", Notation.INFIX, Level.ADDITIVE,
                        List.of(Signature.of(Kind.POSITION, Kind.POSITION, Kind.VECTOR),
                                Signature.of(Kind.VECTOR, Kind.POSITION, Kind.POSITION)),
                        (operands, context) -> operands.get(1) instanceof Value.Position other
                                ? Components.pairwise(position(operands.get(0)), other.components(), Math::subtractExact, Value.Vector::new)
                                : Components.pairwise(position(operands.get(0)), vector(operands.get(1)), Math::subtractExact, Value.Position::new)),
                new DefinedOperation(OperationExpression.INDEX, Notation.INFIX, Level.INDEX,
                        List.of(Signature.of(Kind.NUMBER, Kind.POSITION, Kind.NUMBER)),
                        PositionOperations::component),
                new DefinedOperation("POSITION", Notation.FUNCTION, Level.INDEX,
                        List.of(Signature.of(Kind.POSITION, Kind.VECTOR)),
                        PositionOperations::place));
    }

    /**
     * The component of a position relative to the origin the line counts from.
     */
    private static OperationResult component(List<Value> operands, IEvaluationContext context) {
        List<Long> components = position(operands.get(0));
        long index = ((Value.Number) operands.get(1)).value();
        OperationResult absolute = Components.component(components, index, "a position");
        if (!(absolute instanceof OperationResult.Value value)) {
            return absolute;
        }
        Optional<Value.Position> origin = context.origin();
        if (origin.isEmpty()) {
            return new OperationResult.NotYet();
        }
        if (origin.get().components().size() != components.size()) {
            return new OperationResult.Error(Components.expectedComponents(origin.get().components().size()));
        }
        long relative;
        try {
            relative = Math.subtractExact(((Value.Number) value.value()).value(), origin.get().components().get((int) index));
        } catch (ArithmeticException overflow) {
            return new OperationResult.Error(Components.OVERFLOW);
        }
        return new OperationResult.Value(new Value.Number(relative));
    }

    /**
     * The place a vector names relative to the origin the line counts from.
     */
    private static OperationResult place(List<Value> operands, IEvaluationContext context) {
        Optional<Value.Position> origin = context.origin();
        if (origin.isEmpty()) {
            return new OperationResult.NotYet();
        }
        return Components.pairwise(origin.get().components(), vector(operands.get(0)), Math::addExact, Value.Position::new);
    }

    private static List<Long> vector(Value value) {
        return ((Value.Vector) value).components();
    }

    private static List<Long> position(Value value) {
        return ((Value.Position) value).components();
    }
}
