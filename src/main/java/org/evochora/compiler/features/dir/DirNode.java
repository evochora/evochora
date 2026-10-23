package org.evochora.compiler.features.dir;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.ISourceLocatable;
import org.evochora.compiler.model.ast.OperandNode;

import java.util.List;

/**
 * An AST node that represents a <code>.dir</code> directive.
 *
 * @param mode Either the direction written out or a rotation of the current one.
 * @param sourceInfo The source location of the directive.
 */
public record DirNode(
        Mode mode,
        SourceInfo sourceInfo
) implements AstNode, ISourceLocatable {

    /**
     * The two forms a <code>.DIR</code> directive can take: a direction written out, or a
     * rotation of the direction currently in effect.
     */
    public sealed interface Mode {

        /**
         * A direction given as a vector, one component per dimension of the world.
         *
         * @param vector The operand whose value is the direction: a vector written out, a name or a
         *               computation.
         */
        record Absolute(OperandNode vector) implements Mode {}

        /**
         * A rotation of the current direction by 90 degrees in the plane spanned by two axes.
         *
         * @param forward Whether the component on {@code axisA} turns towards {@code axisB},
         *                which is how the marker {@code @+} reads; {@code false} turns the other
         *                way.
         * @param axisA The first axis of the plane.
         * @param axisB The second axis of the plane.
         */
        record Rotation(boolean forward, int axisA, int axisB) implements Mode {}
    }

    @Override
    public List<AstNode> getChildren() {
        // A written-out direction has the vector as its child; a rotation names no node of its own.
        return mode instanceof Mode.Absolute absolute ? List.of(absolute.vector()) : List.of();
    }

    /**
     * Phase 6 puts the vector of a written-out direction it resolved into the node through this;
     * a rotation has no child and stays as it is.
     */
    @Override
    public AstNode reconstructWithChildren(List<AstNode> newChildren) {
        return mode instanceof Mode.Absolute
                ? new DirNode(new Mode.Absolute((OperandNode) newChildren.getFirst()), sourceInfo)
                : this;
    }
}
