package org.evochora.compiler.frontend.parser.features.def;

import org.evochora.compiler.model.Token;
import org.evochora.compiler.frontend.parser.ast.AstNode;

import java.util.List;

/**
 * An AST node that represents a <code>.define</code> directive.
 *
 * @param name  The token of the constant name.
 * @param value The AST node that represents the value of the constant.
 */
public record DefineNode(
        Token name,
        AstNode value
) implements AstNode {

    @Override
    public List<AstNode> getChildren() {
        return List.of(value);
    }
}