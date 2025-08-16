package org.evochora.compiler.frontend.parser.features.proc;

import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.ast.AstNode;

import java.util.List;

/**
 * Ein AST-Knoten, der eine Prozedur-Definition repräsentiert (.PROC ... .ENDP).
 *
 * @param name Das Token, das den Namen der Prozedur enthält.
 * @param exported Ob die Prozedur exportiert ist (Sichtbarkeit für andere Module).
 * @param parameters Eine Liste von Tokens, die die Parameter der Prozedur darstellen (aus .WITH).
 * @param body Eine Liste von AST-Knoten, die den Inhalt der Prozedur darstellen.
 */
public record ProcedureNode(
        Token name,
        boolean exported,
        List<Token> parameters,
        List<AstNode> body
) implements AstNode {

    @Override
    public List<AstNode> getChildren() {
        // Die Kinder einer Prozedur sind alle Anweisungen in ihrem Körper.
        return body;
    }
}