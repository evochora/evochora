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
     * @return An AST node representing this statement, or {@code null} if the
     *         statement does not produce a node (e.g., .DEFINE, .REG).
     */
    AstNode parse(ParsingContext context);
}
