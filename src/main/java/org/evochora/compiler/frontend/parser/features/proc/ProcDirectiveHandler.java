package org.evochora.compiler.frontend.parser.features.proc;

import org.evochora.compiler.frontend.directive.IDirectiveHandler;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.CompilerPhase;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ParsingContext;

import java.util.ArrayList;
import java.util.List;

public class ProcDirectiveHandler implements IDirectiveHandler {
    @Override
    public CompilerPhase getPhase() {
        return CompilerPhase.PARSING;
    }

    @Override
    public AstNode parse(ParsingContext context) {
        context.advance(); // .PROC konsumieren

        Token procName = context.consume(TokenType.IDENTIFIER, "Expected procedure name after .PROC.");

        List<Token> parameters = new ArrayList<>();
        // Prüfe auf eine optionale WITH-Klausel (als Keyword oder Direktive)
        boolean hasWith = (context.check(TokenType.IDENTIFIER) && context.peek().text().equalsIgnoreCase("WITH")) ||
                          (context.check(TokenType.DIRECTIVE) && context.peek().text().equalsIgnoreCase(".WITH"));

        if (hasWith) {
            context.advance(); // WITH oder .WITH konsumieren
            while (!context.isAtEnd() && !context.check(TokenType.NEWLINE)) {
                parameters.add(context.consume(TokenType.REGISTER, "Expected a register as a parameter in .WITH clause."));
            }
        }

        if (!context.isAtEnd()) {
             context.consume(TokenType.NEWLINE, "Expected newline after .PROC declaration.");
        }

        Parser parser = (Parser) context;
        List<AstNode> body = new ArrayList<>();
        while (!context.isAtEnd() && !(context.check(TokenType.DIRECTIVE) && context.peek().text().equalsIgnoreCase(".ENDP"))) {
            AstNode statement = parser.declaration();
            if (statement != null) {
                body.add(statement);
            }
        }

        context.consume(TokenType.DIRECTIVE, "Expected .ENDP to close procedure block.");

        ProcedureNode procNode = new ProcedureNode(procName, parameters, body);
        parser.registerProcedure(procNode);
        return procNode;
    }
}
