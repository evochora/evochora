package org.evochora.compiler.features.control;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IJumpTarget;
import org.evochora.compiler.model.ast.ISourceLocatable;

import java.util.ArrayList;
import java.util.List;

/**
 * An AST node that represents a control block ({@code .CONTROL <Block>} … {@code .ENDCONTROL}).
 * The block is its own head: it holds the statements before its first {@code .CASE} and then its
 * cases. Its name is the label before its first statement, on the level around the block.
 * <p>
 * The children of a block are its statements followed by its cases;
 * {@link #reconstructWithChildren} tells the two apart by type, so a statement is never a
 * {@link ControlCase}.
 *
 * @param name          The name of the block.
 * @param exported      Whether {@code EXPORT} stands before the {@code .CONTROL}.
 * @param statements    The statements of the head, before the first case, in text order.
 * @param cases         The cases of the block, in text order.
 * @param endExported   Whether {@code EXPORT} stands before the {@code .ENDCONTROL}.
 * @param sourceInfo    Source location of the block name.
 * @param endSourceInfo Source location of the {@code .ENDCONTROL}.
 */
public record ControlNode(
        String name,
        boolean exported,
        List<AstNode> statements,
        List<ControlCase> cases,
        boolean endExported,
        SourceInfo sourceInfo,
        SourceInfo endSourceInfo
) implements AstNode, ISourceLocatable, IJumpTarget {

    @Override
    public List<AstNode> getChildren() {
        List<AstNode> children = new ArrayList<>(statements.size() + cases.size());
        children.addAll(statements);
        children.addAll(cases);
        return children;
    }

    @Override
    public AstNode reconstructWithChildren(List<AstNode> newChildren) {
        List<AstNode> newStatements = new ArrayList<>();
        List<ControlCase> newCases = new ArrayList<>();
        for (AstNode child : newChildren) {
            if (child instanceof ControlCase controlCase) {
                newCases.add(controlCase);
            } else {
                newStatements.add(child);
            }
        }
        return new ControlNode(name, exported, newStatements, newCases, endExported, sourceInfo, endSourceInfo);
    }
}
