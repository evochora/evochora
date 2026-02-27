package org.evochora.compiler.frontend.parser;

import org.evochora.compiler.frontend.parser.ast.AstNode;

/**
 * Handler interface for directives processed during the parsing phase.
 * Parser handlers consume directive tokens and produce AST nodes.
 */
public interface IParserDirectiveHandler {

    /**
     * Parses the directive and its arguments from the token stream.
     *
     * @param context The parsing context providing access to the token stream.
     * @return An AST node representing this directive, or {@code null} if the
     *         directive does not produce a node (e.g., .DEFINE, .REG).
     */
    AstNode parse(ParsingContext context);
}
