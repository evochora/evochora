package org.evochora.compiler.features.org;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.ISourceLocatable;
import org.evochora.compiler.model.ast.OperandNode;

import java.util.List;

/**
 * An AST node that represents an <code>.org</code> directive.
 * <p>
 * Each piece of the directive's {@code |}-list is one component, a number. A directive of one
 * unmarked piece may instead give the whole origin at once: a vector, counted from the origin of
 * the enclosing module, or a position, a place of the program.
 *
 * @param components The components, one operand per piece, in order.
 * @param relative   One entry per component, {@code true} where the component was written with
 *                   the relative marker and counts from the layout cursor instead of the origin of
 *                   the enclosing module.
 * @param sourceInfo The source location of the directive.
 */
public record OrgNode(
        List<OperandNode> components,
        List<Boolean> relative,
        SourceInfo sourceInfo
) implements AstNode, ISourceLocatable {

    /**
     * Copies the lists.
     */
    public OrgNode {
        components = List.copyOf(components);
        relative = List.copyOf(relative);
    }

    @Override
    public List<AstNode> getChildren() {
        return List.copyOf(components);
    }

    /**
     * Phase 6 puts the components it resolved into the node through this, in the order of
     * {@link #getChildren()}.
     */
    @Override
    public AstNode reconstructWithChildren(List<AstNode> newChildren) {
        return new OrgNode(newChildren.stream().map(child -> (OperandNode) child).toList(), relative, sourceInfo);
    }
}
