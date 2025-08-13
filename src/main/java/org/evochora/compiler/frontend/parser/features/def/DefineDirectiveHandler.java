package org.evochora.compiler.frontend.parser.features.def;

import org.evochora.compiler.frontend.CompilerPhase;
import org.evochora.compiler.frontend.directive.IDirectiveHandler;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.frontend.parser.ast.AstNode;

/**
 * Handler für die .DEFINE-Direktive.
 * Parst eine Konstantendefinition und erzeugt einen DefineNode im AST.
 */
public class DefineDirectiveHandler implements IDirectiveHandler {

    @Override
    public CompilerPhase getPhase() {
        return CompilerPhase.PARSING;
    }

    @Override
    public AstNode parse(ParsingContext context) {
        context.advance(); // .DEFINE konsumieren

        Token name = context.consume(TokenType.IDENTIFIER, "Expected a constant name after .DEFINE.");
        AstNode valueNode = ((Parser) context).expression();

        if (name == null || valueNode == null) {
            // Ein Fehler ist beim Parsen der Argumente aufgetreten, der bereits gemeldet wurde.
            return null;
        }

        return new DefineNode(name, valueNode);
    }
}