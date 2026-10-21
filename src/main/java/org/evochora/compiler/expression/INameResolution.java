package org.evochora.compiler.expression;

import org.evochora.compiler.model.expression.Kind;
import org.evochora.compiler.model.expression.NameExpression;

import java.util.Optional;

/**
 * Answers the names of a computation for the {@link Evaluator}. The phase that evaluates supplies
 * it: the frontend over its symbol table, the layout over the positions it has laid out.
 */
@FunctionalInterface
public interface INameResolution {

    /**
     * The predefined name of the cursor, the cell the next code would go to.
     */
    String CURSOR = "@";

    /**
     * Answers what a name stands for.
     *
     * @param name The name, with the position it is written at.
     * @return The answer.
     */
    NameAnswer resolve(NameExpression name);

    /**
     * Answers the cursor the way every resolution of the frontend does: a position that only
     * the layout knows. A frontend resolution asks this first and resolves the name itself when
     * it is empty.
     *
     * @param name The name.
     * @return "later, a position, under {@code @}" for the cursor; empty for every other name.
     */
    static Optional<NameAnswer> cursorLater(NameExpression name) {
        return CURSOR.equals(name.name())
                ? Optional.of(new NameAnswer.Later(Kind.POSITION, CURSOR))
                : Optional.empty();
    }
}
