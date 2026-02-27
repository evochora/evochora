package org.evochora.compiler.frontend.parser.features.label;

import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.SourceLocatable;

import java.util.List;

/**
 * An AST node that represents a label definition (e.g., "L1:" or "L1: EXPORT").
 *
 * @param labelToken The token containing the name of the label.
 * @param statement The statement (typically an instruction) that follows this label.
 * @param exported Whether this label is exported for cross-file visibility.
 */
public record LabelNode(
        Token labelToken,
        AstNode statement,
        boolean exported
) implements AstNode, SourceLocatable {

    @Override
    public String getSourceFileName() {
        return labelToken.fileName();
    }

    /**
     * Constructs a non-exported label node. This constructor provides backwards
     * compatibility for existing code that does not use the export feature.
     *
     * @param labelToken The token containing the name of the label.
     * @param statement The statement that follows this label.
     */
    public LabelNode(Token labelToken, AstNode statement) {
        this(labelToken, statement, false);
    }

    @Override
    public List<AstNode> getChildren() {
        // A label has exactly one child: the statement that follows it.
        return statement != null ? List.of(statement) : List.of();
    }

    @Override
    public AstNode reconstructWithChildren(List<AstNode> newChildren) {
        // Create a new LabelNode with the new statement (first child)
        AstNode newStatement = newChildren.isEmpty() ? null : newChildren.get(0);
        return new LabelNode(labelToken, newStatement, exported);
    }
}
