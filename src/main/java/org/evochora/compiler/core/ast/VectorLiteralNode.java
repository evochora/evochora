package org.evochora.compiler.core.ast;

import org.evochora.compiler.core.Token;

import java.util.List;

/**
 * Ein AST-Knoten, der ein Vektor-Literal repräsentiert, z.B. "3|21".
 *
 * @param components Eine Liste von Tokens, die die einzelnen Zahlen des Vektors darstellen.
 */
public record VectorLiteralNode(
        List<Token> components
) implements AstNode {
}
