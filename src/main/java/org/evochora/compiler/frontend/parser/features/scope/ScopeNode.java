package org.evochora.compiler.frontend.parser.features.scope;

import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.ast.AstNode;

import java.util.List;

/**
 * An AST node that represents a named scope (<code>.scope</code> ... <code>.ends</code>).
 *
 * @param name The token containing the name of the scope.
 * @param body A list of AST nodes representing the content of the scope.
 */
public record ScopeNode(
        Token name,
        List<AstNode> body
) implements AstNode {

    @Override
    public List<AstNode> getChildren() {
        // The children of a scope are all the statements in its body.
        return body;
    }
}