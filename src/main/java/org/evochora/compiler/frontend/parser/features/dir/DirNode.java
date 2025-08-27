package org.evochora.compiler.frontend.parser.features.dir;

import org.evochora.compiler.frontend.parser.ast.AstNode;

import java.util.List;

/**
 * An AST node that represents a <code>.dir</code> directive.
 *
 * @param directionVector The vector literal that specifies the direction.
 */
public record DirNode(
        AstNode directionVector
) implements AstNode {

    @Override
    public List<AstNode> getChildren() {
        // The child of a .DIR node is the vector that defines the direction.
        return List.of(directionVector);
    }
}