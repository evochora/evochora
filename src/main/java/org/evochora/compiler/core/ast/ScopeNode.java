package org.evochora.compiler.core.ast;

import org.evochora.compiler.core.Token;

import java.util.List;

/**
 * Ein AST-Knoten, der einen benannten Geltungsbereich repräsentiert (.SCOPE ... .ENDS).
 *
 * @param name Das Token, das den Namen des Scopes enthält.
 * @param body Eine Liste von AST-Knoten, die den Inhalt des Scopes darstellen.
 */
public record ScopeNode(
        Token name,
        List<AstNode> body
) implements AstNode {
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
