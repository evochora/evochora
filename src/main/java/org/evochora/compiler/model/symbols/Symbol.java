package org.evochora.compiler.model.symbols;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;

/**
 * Represents a single symbol (e.g., a label, a constant, or a procedure)
 * in the symbol table.
 *
 * @param name       The symbol name.
 * @param sourceInfo The source location where this symbol was defined.
 * @param type       The type of the symbol.
 * @param node       The AST node associated with this symbol (e.g., ProcedureNode).
 * @param exported   Whether this symbol is visible one level further out: to the module's
 *                   importers for a name on the module level, to the module for a name inside a
 *                   procedure. For an import alias it means that the import is passed on, so a
 *                   path from outside may continue through the alias.
 */
public record Symbol(String name, SourceInfo sourceInfo, Type type, AstNode node, boolean exported) {
    /**
     * The type of a symbol in the symbol table.
     */
    public enum Type {
        /** A label defined in the source code. */
        LABEL,
        /** A constant defined with .CONST. */
        CONSTANT,
        /** A procedure defined with .PROC. */
        PROCEDURE,
        /** A data procedure parameter (REF/VAL, resolves to FDR). */
        PARAMETER_DATA,
        /** A location procedure parameter (LREF/LVAL, resolves to FLR). */
        PARAMETER_LOCATION,
        /** A module alias defined with .IMPORT AS or .REQUIRE AS. */
        MODULE_ALIAS,
        /** A data register alias (.REG %X %DR0, target is a data bank). */
        REGISTER_ALIAS_DATA,
        /** A location register alias (.REG %X %LR0, target is a location bank). */
        REGISTER_ALIAS_LOCATION
    }

    /**
     * Constructor for symbols without an associated AST node and not exported.
     * @param name       The symbol name.
     * @param sourceInfo The source location.
     * @param type       The symbol's type.
     */
    public Symbol(String name, SourceInfo sourceInfo, Type type) {
        this(name, sourceInfo, type, null, false);
    }

    /**
     * Constructor for symbols with an AST node but not exported.
     * @param name       The symbol name.
     * @param sourceInfo The source location.
     * @param type       The symbol's type.
     * @param node       The associated AST node.
     */
    public Symbol(String name, SourceInfo sourceInfo, Type type, AstNode node) {
        this(name, sourceInfo, type, node, false);
    }
}
