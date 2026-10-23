package org.evochora.compiler.features.expression;

import org.evochora.compiler.expression.IOperation;
import org.evochora.compiler.expression.IOperationGroup;
import org.evochora.compiler.expression.Level;
import org.evochora.compiler.expression.Notation;
import org.evochora.compiler.expression.OperationResult;
import org.evochora.compiler.expression.Signature;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.compiler.model.expression.Kind;
import org.evochora.compiler.model.expression.Value;

import java.util.List;

/**
 * The operations between molecule types and typed values: the typing operator {@code T:n}, which
 * gives a number a molecule type; {@code TYPE(x)}, the type of a typed value; and
 * {@code NUMBER(x)}, its number. So {@code TYPE(UNIT):7} is {@code ENERGY:7} for a constant
 * {@code UNIT} of {@code ENERGY:50}.
 * <p>
 * The typing operator takes a molecule type the instruction set knows and a number; a type is a
 * value of its own and never becomes a cell.
 */
final class TypingOperations implements IOperationGroup {

    private final IInstructionSet isa;

    /**
     * Creates the group for a compilation.
     *
     * @param isa The instruction set, which names the molecule types.
     */
    TypingOperations(IInstructionSet isa) {
        this.isa = isa;
    }

    @Override
    public List<IOperation> operations() {
        return List.of(
                new DefinedOperation(":", Notation.INFIX, Level.TYPING,
                        List.of(Signature.of(Kind.TYPED, Kind.TYPE, Kind.NUMBER)),
                        (operands, context) -> {
                            String type = ((Value.Type) operands.get(0)).name();
                            if (isa.moleculeType(type).isEmpty()) {
                                return new OperationResult.Error("'" + type + "' is not a molecule type.");
                            }
                            return new OperationResult.Value(new Value.Typed(type, ((Value.Number) operands.get(1)).value()));
                        }),
                new DefinedOperation("TYPE", Notation.FUNCTION, Level.INDEX,
                        List.of(Signature.of(Kind.TYPE, Kind.TYPED)),
                        (operands, context) -> new OperationResult.Value(new Value.Type(((Value.Typed) operands.get(0)).type()))),
                new DefinedOperation("NUMBER", Notation.FUNCTION, Level.INDEX,
                        List.of(Signature.of(Kind.NUMBER, Kind.TYPED)),
                        (operands, context) -> new OperationResult.Value(new Value.Number(((Value.Typed) operands.get(0)).number()))));
    }
}
