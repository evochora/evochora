package org.evochora.compiler.frontend.parser.features.label;

import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.ast.AstNode;

import java.util.List;

/**
 * An AST node that represents a label definition (e.g., "L1:").
 *
 * @param labelToken The token containing the name of the label.
 * @param statement The statement (typically an instruction) that follows this label.
 */
public record LabelNode(
        Token labelToken,
        AstNode statement
) implements AstNode {

    @Override
    public List<AstNode> getChildren() {
        // A label has exactly one child: the statement that follows it.
        return statement != null ? List.of(statement) : List.of();
    }
    
    @Override
    public AstNode reconstructWithChildren(List<AstNode> newChildren) {
        // Create a new LabelNode with the new statement (first child)
        AstNode newStatement = newChildren.isEmpty() ? null : newChildren.get(0);
        return new LabelNode(labelToken, newStatement);
    }
}