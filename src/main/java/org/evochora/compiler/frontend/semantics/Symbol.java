package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.ast.AstNode;

/**
 * Represents a single symbol (e.g., a label, a constant, or a procedure)
 * in the symbol table.
 *
 * @param name The token of the symbol, containing name, line, and column.
 * @param type The type of the symbol.
 * @param node The AST node associated with this symbol (e.g., ProcedureNode).
 */
public record Symbol(Token name, Type type, AstNode node) {
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
        VARIABLE,
        /** A register alias defined with .REG or .PREG. */
        ALIAS
    }

    /**
     * Backward-compatible constructor for symbols without an associated AST node.
     * @param name The symbol's name token.
     * @param type The symbol's type.
     */
    public Symbol(Token name, Type type) {
        this(name, type, null);
    }
}