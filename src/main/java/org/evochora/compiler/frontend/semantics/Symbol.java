package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.frontend.lexer.Token;

/**
 * Represents a single symbol (e.g., a label, a constant, or a procedure)
 * in the symbol table.
 *
 * @param name The token of the symbol, containing name, line, and column.
 * @param type The type of the symbol.
 */
public record Symbol(Token name, Type type) {
    /**
     * The type of a symbol in the symbol table.
     */
    public enum Type {
        /** A label defined in the source code. */
        LABEL,
        /** A constant defined with .DEFINE. */
        CONSTANT,
        /** A procedure defined with .PROC. */
        PROCEDURE,
        /** A variable, such as a procedure parameter. */
        VARIABLE // For future extensions
    }
}