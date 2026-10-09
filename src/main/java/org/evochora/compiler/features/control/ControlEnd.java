package org.evochora.compiler.features.control;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IJumpTarget;
import org.evochora.compiler.model.ast.ISourceLocatable;

import java.util.List;

/**
 * The node of the label {@code END} of a control block, the place behind its
 * {@code .ENDCONTROL}. It is the node the symbol {@code END} is defined with on the block's level
 * and never stands in the tree; the block itself carries the position of its end.
 *
 * @param sourceInfo Source location of the {@code .ENDCONTROL}.
 */
public record ControlEnd(SourceInfo sourceInfo) implements AstNode, ISourceLocatable, IJumpTarget {

    @Override
    public List<AstNode> getChildren() {
        return List.of();
    }

    @Override
    public AstNode reconstructWithChildren(List<AstNode> newChildren) {
        return this;
    }
}
