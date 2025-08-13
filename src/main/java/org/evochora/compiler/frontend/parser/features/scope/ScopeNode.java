package org.evochora.compiler.frontend.parser.features.scope;

import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.ast.AstNode;

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
    public List<AstNode> getChildren() {
        // Die Kinder eines Scopes sind alle Anweisungen in seinem Körper.
        return body;
    }
}