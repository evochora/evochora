package org.evochora.compiler.frontend.parser.ast;

import org.evochora.compiler.model.Token;

import java.util.List;

/**
 * An AST node that represents a vector literal, e.g., "3|21".
 *
 * @param components A list of tokens that represent the individual numbers of the vector.
 */
public record VectorLiteralNode(
        List<Token> components
) implements AstNode {
    // This node has no children and inherits the empty list from getChildren().
}