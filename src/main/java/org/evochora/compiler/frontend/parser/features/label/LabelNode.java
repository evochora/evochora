package org.evochora.compiler.frontend.parser.features.label;

import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.ast.AstNode;

import java.util.List;

/**
 * Ein AST-Knoten, der eine Label-Definition repräsentiert (z.B. "L1:").
 *
 * @param labelToken Das Token, das den Namen des Labels enthält.
 * @param statement Das Statement (typischerweise eine Instruktion), das diesem Label folgt.
 */
public record LabelNode(
        Token labelToken,
        AstNode statement
) implements AstNode {

    @Override
    public List<AstNode> getChildren() {
        // Ein Label hat genau ein Kind: das Statement, das darauf folgt.
        return statement != null ? List.of(statement) : List.of();
    }
}