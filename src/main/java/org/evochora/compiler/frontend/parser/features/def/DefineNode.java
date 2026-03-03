package org.evochora.compiler.frontend.parser.features.def;

import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.ast.AstNode;

import java.util.List;

/**
 * An AST node that represents a <code>.define</code> directive (e.g., ".DEFINE X 42" or "EXPORT .DEFINE X 42").
 *
 * @param name     The token of the constant name.
 * @param value    The AST node that represents the value of the constant.
 * @param exported Whether this constant is exported for cross-module visibility.
 */
public record DefineNode(
        Token name,
        AstNode value,
        boolean exported
) implements AstNode {

    /**
     * Constructs a non-exported define node.
     *
     * @param name  The token of the constant name.
     * @param value The AST node that represents the value of the constant.
     */
    public DefineNode(Token name, AstNode value) {
        this(name, value, false);
    }

    @Override
    public List<AstNode> getChildren() {
        return List.of(value);
    }
}