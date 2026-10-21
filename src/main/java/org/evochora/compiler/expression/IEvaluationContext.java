package org.evochora.compiler.expression;

import org.evochora.compiler.model.expression.Value;

import java.util.Optional;

/**
 * What the phase that evaluates knows of the program's layout at the line it evaluates. In the
 * frontend it knows nothing of the kind, and an operation that needs it answers "not yet"; the
 * layout knows both.
 */
public interface IEvaluationContext {

    /**
     * Returns the cursor: the cell the next code would go to, as a position of the program.
     *
     * @return The cursor, or empty where the layout is not known.
     */
    Optional<Value.Position> cursor();

    /**
     * Returns the origin the line counts its vectors from, as a position of the program: the
     * origin of the module, or of the file inside a file it includes.
     *
     * @return The origin, or empty where the layout is not known.
     */
    Optional<Value.Position> origin();

    /**
     * Returns the context of a phase that knows nothing of the layout: no cursor, no origin.
     *
     * @return The context.
     */
    static IEvaluationContext none() {
        return of(Optional.empty(), Optional.empty());
    }

    /**
     * Creates the context of a line whose cursor and origin the layout knows.
     *
     * @param cursor The cursor, as a position of the program.
     * @param origin The origin the line counts its vectors from, as a position of the program.
     * @return The context.
     */
    static IEvaluationContext at(Value.Position cursor, Value.Position origin) {
        return of(Optional.of(cursor), Optional.of(origin));
    }

    private static IEvaluationContext of(Optional<Value.Position> cursor, Optional<Value.Position> origin) {
        return new IEvaluationContext() {
            @Override
            public Optional<Value.Position> cursor() {
                return cursor;
            }

            @Override
            public Optional<Value.Position> origin() {
                return origin;
            }
        };
    }
}
