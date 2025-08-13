package org.evochora.compiler.frontend.parser.features.def;

import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.ast.AstNode;

import java.util.List;

/**
 * Ein AST-Knoten, der eine .DEFINE-Direktive repräsentiert.
 *
 * @param name  Das Token des Konstantennamens.
 * @param value Der AST-Knoten, der den Wert der Konstante darstellt.
 */
public record DefineNode(
        Token name,
        AstNode value
) implements AstNode {

    @Override
    public List<AstNode> getChildren() {
        return List.of(value);
    }
}