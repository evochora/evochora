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
        Parser parser = (Parser) context;
        context.advance(); // .PROC konsumieren

        Token procName = context.consume(TokenType.IDENTIFIER, "Expected procedure name after .PROC.");
        boolean exported = false;
        List<Token> parameters = new ArrayList<>();
        boolean withFound = false;

        // Flexible Schleife zum Parsen von optionalen Keywords wie EXPORT und WITH
        while (!context.isAtEnd() && !context.check(TokenType.NEWLINE)) {
            if (context.check(TokenType.IDENTIFIER)) {
                String keyword = context.peek().text();
                if ("EXPORT".equalsIgnoreCase(keyword)) {
                    context.advance();
                    exported = true;
                } else if ("WITH".equalsIgnoreCase(keyword)) {
                    context.advance();
                    withFound = true;
                    break; // Nach WITH kommen nur noch Parameter
                } else {
                    // Unbekanntes Keyword in der Deklaration
                    context.getDiagnostics().reportError("Unexpected token '" + keyword + "' in procedure declaration.", procName.fileName(), procName.line());
                    break;
                }
            } else {
                break; // Kein Identifier, also keine optionalen Keywords mehr
            }
        }

        if (withFound) {
            while (!context.isAtEnd() && !context.check(TokenType.NEWLINE)) {
                parameters.add(context.consume(TokenType.IDENTIFIER, "Expected a formal parameter name after WITH."));
            }
        }

        if (!context.isAtEnd()) {
            context.consume(TokenType.NEWLINE, "Expected newline after .PROC declaration.");
        }

        // Scope für prozedur-lokale Aliase öffnen
        parser.pushRegisterAliasScope();

        List<AstNode> body = new ArrayList<>();
        while (!context.isAtEnd() && !(context.check(TokenType.DIRECTIVE) && context.peek().text().equalsIgnoreCase(".ENDP"))) {
            if (context.match(TokenType.NEWLINE)) continue;
            AstNode statement = parser.declaration();
            if (statement != null) {
                body.add(statement);
            }
        }

        // Scope für prozedur-lokale Aliase schließen
        parser.popRegisterAliasScope();

        if (context.isAtEnd() || !(context.check(TokenType.DIRECTIVE) && context.peek().text().equalsIgnoreCase(".ENDP"))) {
            context.getDiagnostics().reportError("Expected .ENDP to close procedure block.", "Syntax Error", procName.line());
        } else {
            context.advance(); // .ENDP konsumieren
        }

        ProcedureNode procNode = new ProcedureNode(procName, exported, parameters, body);
        parser.registerProcedure(procNode);
        return procNode;
    }
}