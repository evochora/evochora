package org.evochora.compiler.features.expression;

import org.evochora.compiler.expression.IEvaluationContext;
import org.evochora.compiler.expression.IOperation;
import org.evochora.compiler.expression.Level;
import org.evochora.compiler.expression.Notation;
import org.evochora.compiler.expression.OperationResult;
import org.evochora.compiler.expression.Signature;
import org.evochora.compiler.model.expression.Value;

import java.util.List;
import java.util.function.BiFunction;

/**
 * An operation of the groups of this feature: its key, level and signatures, and the function
 * that computes it.
 *
 * @param name        The name the operation is registered under.
 * @param notation    Where it stands among its operands.
 * @param level       The level it binds at.
 * @param signatures  Its overloads; copied, so that the record is immutable.
 * @param computation What it computes for operands that fit one of the signatures.
 */
record DefinedOperation(String name, Notation notation, Level level, List<Signature> signatures,
                        BiFunction<List<Value>, IEvaluationContext, OperationResult> computation) implements IOperation {

    /**
     * Copies the signatures.
     */
    DefinedOperation {
        signatures = List.copyOf(signatures);
    }

    @Override
    public OperationResult compute(List<Value> operands, IEvaluationContext context) {
        return computation.apply(operands, context);
    }
}
