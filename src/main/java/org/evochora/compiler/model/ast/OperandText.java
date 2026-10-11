package org.evochora.compiler.model.ast;

import java.util.stream.Collectors;

/**
 * The text of an operand as the parser read it, for the artifact: a typed literal as
 * {@code TYPE:value}, a vector as its components joined by {@code |}, a number in decimal, a
 * name as written, a register by its name.
 */
public final class OperandText {

    private OperandText() {
    }

    /**
     * Writes an operand as text.
     *
     * @param operand The operand.
     * @return The text of the operand.
     */
    public static String of(OperandNode operand) {
        return switch (operand) {
            case NumberLiteralNode number -> String.valueOf(number.value());
            case TypedLiteralNode typed -> typed.typeName() + ":" + typed.value();
            case VectorLiteralNode vector -> vector.values().stream().map(String::valueOf).collect(Collectors.joining("|"));
            case IdentifierNode identifier -> identifier.text();
            case RegisterNode register -> register.name();
        };
    }
}
