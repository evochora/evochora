package org.evochora.compiler.features.org;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.ISourceLocatable;

import java.util.List;

/**
 * An AST node that represents an <code>.org</code> directive.
 *
 * @param originVector The vector literal that specifies the origin.
 * @param relative One entry per vector component, {@code true} where the component was written
 *                 with the relative marker and counts from the layout cursor instead of the
 *                 origin of the enclosing module.
 * @param sourceInfo The source location of the directive.
 */
public record OrgNode(
        AstNode originVector,
        List<Boolean> relative,
        SourceInfo sourceInfo
) implements AstNode, ISourceLocatable {

    @Override
    public List<AstNode> getChildren() {
        // The child of an .ORG node is the vector that defines the origin.
        return List.of(originVector);
    }
}
