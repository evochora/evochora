package org.evochora.compiler.frontend.parser.features.require;

import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.ast.AstNode;

/**
 * An AST node that represents a <code>.require</code> directive.
 *
 * @param path The string token of the file path.
 * @param alias The optional alias token (can be null).
 */
public record RequireNode(
        Token path,
        Token alias
) implements AstNode {
    // This node has no children.
}