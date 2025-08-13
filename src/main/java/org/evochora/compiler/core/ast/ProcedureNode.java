package org.evochora.compiler.core.ast;

import org.evochora.compiler.core.Token;

import java.util.List;

import org.evochora.compiler.core.Token;

import java.util.List;

/**
 * Ein AST-Knoten, der eine Prozedur-Definition repräsentiert (.PROC ... .ENDP).
 *
 * @param name Das Token, das den Namen der Prozedur enthält.
 * @param parameters Eine Liste von Tokens, die die Parameter der Prozedur darstellen (aus .WITH).
 * @param body Eine Liste von AST-Knoten, die den Inhalt der Prozedur darstellen.
 */
public record ProcedureNode(
        Token name,
        List<Token> parameters,
        List<AstNode> body
) implements AstNode {
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
