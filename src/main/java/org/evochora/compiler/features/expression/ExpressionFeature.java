package org.evochora.compiler.features.expression;

import org.evochora.compiler.ICompilerFeature;
import org.evochora.compiler.IFeatureRegistrationContext;
import org.evochora.compiler.expression.IOperationGroup;

import java.util.List;

/**
 * Compiler feature for the operations of compile-time computations: the arithmetic, the bitwise
 * operations, the vector operations, the typing operations and the operations on positions,
 * each a group with its rule for the kinds. It registers the lexer symbols the operators need
 * beyond the tokenizer's fixed characters, and no phase handler: the phases that compute read the
 * operations from the registry.
 */
public class ExpressionFeature implements ICompilerFeature {

    @Override
    public String name() {
        return "expression";
    }

    @Override
    public void register(IFeatureRegistrationContext ctx) {
        // Phase 1: Lexing
        ctx.lexerSymbol("+");
        ctx.lexerSymbol("*");
        ctx.lexerSymbol("/");

        // The operations, for every phase that computes
        List<IOperationGroup> groups = List.of(
                new ArithmeticOperations(),
                new BitwiseOperations(),
                new VectorOperations(),
                new TypingOperations(ctx.isa()),
                new PositionOperations());
        groups.forEach(group -> group.operations().forEach(ctx::operation));
    }
}
