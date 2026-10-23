package org.evochora.compiler.expression;

import org.evochora.compiler.model.expression.Kind;

/**
 * What a name in a computation stands for, as an {@link INameResolution} answers it.
 */
public sealed interface NameAnswer permits NameAnswer.Value, NameAnswer.Later, NameAnswer.Reported, NameAnswer.NotUsable {

    /**
     * The name has a value now.
     *
     * @param value The value.
     */
    record Value(org.evochora.compiler.model.expression.Value value) implements NameAnswer {
    }

    /**
     * The name has a value only a later phase knows, such as a label's position or the cursor.
     *
     * @param kind          The kind the value will have.
     * @param qualifiedName The name the later phase knows it under; {@code @} for the cursor.
     */
    record Later(Kind kind, String qualifiedName) implements NameAnswer {
    }

    /**
     * The name has no value, and its definition has said why already.
     */
    record Reported() implements NameAnswer {
    }

    /**
     * The name cannot stand in a computation.
     *
     * @param explanation A full sentence, names quoted, such as
     *                    {@code Register '%DR0' cannot be used in an expression.} or
     *                    {@code 'FOO' is not defined.}
     */
    record NotUsable(String explanation) implements NameAnswer {
    }
}
