package org.evochora.compiler.frontend.parser.features.org;

import org.evochora.compiler.frontend.parser.ast.AstNode;

import java.util.List;

/**
 * An AST node that represents an <code>.org</code> directive.
 *
 * @param originVector The vector literal that specifies the origin.
 */
public record OrgNode(
        AstNode originVector
) implements AstNode {

    @Override
    public List<AstNode> getChildren() {
        // The child of an .ORG node is the vector that defines the origin.
        return List.of(originVector);
    }
}