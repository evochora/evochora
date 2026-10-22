package org.evochora.compiler.expression;

import org.evochora.compiler.api.PositionedNote;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.expression.Expression;
import org.evochora.compiler.model.expression.OperationExpression;
import org.evochora.compiler.model.expression.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * The notes {@code [=value]} a computation shows in the Source View, each at the end of the
 * expression it belongs to (see {@link Expression#end()}).
 * <p>
 * A computation shows its value at its end, unless its top node is the typing {@code T:n}: the
 * typing is the notation of a typed value and shows nothing itself. Each of its two operands that
 * is an operation shows its own part of the typed value instead, the type part its type and the
 * number part its number. An operand that is a literal shows nothing, since it is written as it
 * is, and so does a name, whose value the Source View shows as that of every constant. Only the
 * top node is looked at: a typing inside a bigger computation is a part of it like any other.
 * A vector {@code a|b|c} is the notation of a vector in the same way: it shows nothing itself,
 * and each of its parts that is an operation shows its own component.
 * A note whose position stands in no file is left out, as a source view cannot show it.
 */
public final class ValueNotes {

    private ValueNotes() {
    }

    /**
     * Returns the notes of a computation whose value is known.
     *
     * @param expression The computation.
     * @param value      Its value.
     * @return The notes, in the order of their positions; empty if none is shown.
     * @throws IllegalStateException if the top node is the typing and the value is no typed value,
     *                               or the vector and the value is no vector.
     */
    public static List<PositionedNote> of(Expression expression, Value value) {
        List<PositionedNote> notes = new ArrayList<>();
        if (expression instanceof OperationExpression typing && isTyping(typing)) {
            if (!(value instanceof Value.Typed typed)) {
                throw new IllegalStateException("The typing " + typing.text() + " gave no typed value: " + value.text());
            }
            notePart(notes, typing.operands().get(0), typed.type());
            notePart(notes, typing.operands().get(1), Long.toString(typed.number()));
        } else if (expression instanceof OperationExpression chain && isChain(chain)) {
            if (!(value instanceof Value.Vector vector)) {
                throw new IllegalStateException("The vector " + chain.text() + " gave no vector: " + value.text());
            }
            for (int i = 0; i < chain.operands().size(); i++) {
                notePart(notes, chain.operands().get(i), Long.toString(vector.components().get(i)));
            }
        } else {
            note(notes, expression.end(), value.text());
        }
        return List.copyOf(notes);
    }

    private static boolean isChain(OperationExpression operation) {
        return OperationRegistry.VECTOR.equals(operation.operation());
    }

    private static boolean isTyping(OperationExpression operation) {
        return OperationRegistry.TYPING.equals(operation.operation()) && operation.operands().size() == 2;
    }

    private static void notePart(List<PositionedNote> notes, Expression part, String text) {
        if (part instanceof OperationExpression) {
            note(notes, part.end(), text);
        }
    }

    private static void note(List<PositionedNote> notes, SourceInfo end, String text) {
        if (end.inAFile()) {
            notes.add(new PositionedNote(end, "[=" + text + "]"));
        }
    }
}
