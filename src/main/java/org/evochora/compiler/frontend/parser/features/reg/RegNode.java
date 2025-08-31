package org.evochora.compiler.frontend.parser.features.reg;

import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.ast.AstNode;

import java.util.List;

/**
 * An AST node that represents a <code>.REG</code> directive.
 *
 * @param alias    The token of the alias name.
 * @param register The token of the target register.
 */
public record RegNode(
        Token alias,
        Token register
) implements AstNode {

    @Override
    public List<AstNode> getChildren() {
        return List.of();
    }
}
