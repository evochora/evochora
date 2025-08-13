package org.evochora.compiler.core;

/**
 * Repräsentiert ein einzelnes Token, das vom {@link org.evochora.compiler.core.phases.Lexer} aus dem Quellcode extrahiert wurde.
 *
 * @param type Der Typ des Tokens (z.B. Opcode, Register, Zahl).
 * @param text Der exakte Text des Tokens aus dem Quellcode.
 * @param value Der verarbeitete Wert des Tokens (z.B. der Integer-Wert einer Zahl).
 * @param line Die Zeilennummer, in der das Token gefunden wurde.
 * @param column Die Spaltennummer, in der das Token beginnt.
 */

import org.evochora.compiler.core.phases.Lexer;

/**
 * Repräsentiert ein einzelnes Token, das vom {@link Lexer} aus dem Quellcode extrahiert wurde.
 */
public record Token(
        TokenType type,
        String text,
        Object value,
        int line,
        int column
) {
}
