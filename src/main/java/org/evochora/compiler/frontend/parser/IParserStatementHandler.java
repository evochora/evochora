package org.evochora.compiler.frontend.parser;

import org.evochora.compiler.model.ast.AstNode;

/**
 * Handler interface for statements processed during the parsing phase.
 * Statement handlers consume tokens and produce AST nodes.
 * Statements are dispatched by keyword (directives, opcodes, or other identifiers).
 */
public interface IParserStatementHandler {

    /**
     * Parses the statement and its arguments from the token stream.
     *
     * @param context The parsing context providing access to the token stream.
     * @return The parsed AST node, or {@code null} if the statement does not produce
     *         a node (e.g., .DEFINE, .REG) or if a parse error was reported via the
     *         parsing context diagnostics. Handlers must never return {@code null}
     *         without first reporting an error or being a legitimately void statement.
     */
    AstNode parse(ParsingContext context);

    /**
     * Returns whether this handler supports the EXPORT keyword preceding the statement.
     * Handlers that return false will cause a compilation error if EXPORT is used.
     *
     * @return true if EXPORT is valid before this statement, false otherwise.
     */
    default boolean supportsExport() {
        return false;
    }
}
