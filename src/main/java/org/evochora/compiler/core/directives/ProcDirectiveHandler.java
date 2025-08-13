package org.evochora.compiler.core.directives;

import org.evochora.compiler.core.CompilerPhase;
import org.evochora.compiler.core.Parser;
import org.evochora.compiler.core.Token;
import org.evochora.compiler.core.TokenType;
import org.evochora.compiler.core.ast.AstNode;
import org.evochora.compiler.core.ast.ProcedureNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Handler für die .PROC- und .ENDP-Direktiven.
 * Parst einen gesamten Prozedur-Block.
 */
public class ProcDirectiveHandler implements IDirectiveHandler {
    @Override
    public CompilerPhase getPhase() {
        return CompilerPhase.PARSING;
    }

    @Override
    public AstNode parse(Parser parser) {
        parser.advance(); // .PROC konsumieren

        Token procName = parser.consume(TokenType.IDENTIFIER, "Expected procedure name after .PROC.");

        List<Token> parameters = new ArrayList<>();
        // Prüfe auf eine optionale WITH-Klausel (als Keyword oder Direktive), die auf der gleichen Zeile folgt.
        boolean hasWith = (parser.check(TokenType.IDENTIFIER) && parser.peek().text().equalsIgnoreCase("WITH"))
                || (parser.check(TokenType.DIRECTIVE) && parser.peek().text().equalsIgnoreCase(".WITH"));

        if (hasWith) {
            parser.advance(); // WITH oder .WITH konsumieren
            while (!parser.isAtEnd() && !parser.check(TokenType.NEWLINE)) {
                parameters.add(parser.consume(TokenType.REGISTER, "Expected a register as a parameter in WITH clause."));
            }
        }

        // Jede Deklaration muss mit einem Newline enden (oder EOF)
        if (!parser.isAtEnd()) {
             parser.consume(TokenType.NEWLINE, "Expected newline after .PROC declaration.");
        }

        List<AstNode> body = new ArrayList<>();
        while (!parser.isAtEnd() && !(parser.check(TokenType.DIRECTIVE) && parser.peek().text().equalsIgnoreCase(".ENDP"))) {
            // Überspringe leere Zeilen innerhalb des Prozedur-Bodys
            if (parser.check(TokenType.NEWLINE)) {
                parser.advance();
                continue;
            }

            AstNode statement = parser.declaration();
            if (statement != null) {
                body.add(statement);
            } else if (!parser.getDiagnostics().hasErrors()){
                // Wenn declaration() null zurückgibt, aber keinen Fehler gemeldet hat,
                // ist es wahrscheinlich eine leere Zeile, die wir schon behandelt haben.
                // Aber um Endlosschleifen zu vermeiden, wenn ein Handler null zurückgibt, gehen wir einen Schritt weiter.
                parser.advance();
            }
        }

        parser.consume(TokenType.DIRECTIVE, "Expected .ENDP to close procedure block.");

        ProcedureNode procNode = new ProcedureNode(procName, parameters, body);
        parser.registerProcedure(procNode);
        return procNode;
    }
}
