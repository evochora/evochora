package org.evochora.compiler.frontend.parser.features.proc;

import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.ast.AstNode;

/**
 * An AST node that represents an <code>.export</code> directive within a procedure.
 *
 * @param exportedName The token of the name being exported.
 */
public record ExportNode(
        Token exportedName
) implements AstNode {
    // This node has no children, so the default method from AstNode is used.
}