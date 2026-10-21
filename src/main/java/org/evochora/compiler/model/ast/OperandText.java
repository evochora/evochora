package org.evochora.compiler.model.ast;

/**
 * The text of an operand as the parser read it, for the artifact: a literal as its value is
 * written, a name as written, a register by its name, a computation as its tree writes it.
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
            case LiteralNode literal -> literal.value().text();
            case IdentifierNode identifier -> identifier.text();
            case RegisterNode register -> register.name();
            case ExpressionNode expression -> expression.expression().text();
        };
    }
}
