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
        if (procName == null) return null;
        parser.consume(TokenType.NEWLINE, "Expected newline after procedure name.");

        List<Token> parameters = new ArrayList<>();
        // Prüfe auf eine optionale WITH-Klausel (als Keyword oder Direktive)
        boolean hasWith = (parser.check(TokenType.IDENTIFIER) && parser.peek().text().equalsIgnoreCase("WITH")) ||
                          (parser.check(TokenType.DIRECTIVE) && parser.peek().text().equalsIgnoreCase(".WITH"));

        if (hasWith) {
            parser.advance(); // WITH oder .WITH konsumieren
            while (!parser.check(TokenType.NEWLINE) && !parser.isAtEnd()) {
                parameters.add(parser.consume(TokenType.REGISTER, "Expected a register as a parameter in .WITH clause."));
            }
            parser.consume(TokenType.NEWLINE, "Expected newline after .WITH parameters.");
        }

        List<AstNode> body = new ArrayList<>();
        while (!parser.isAtEnd() && !(parser.check(TokenType.DIRECTIVE) && parser.peek().text().equalsIgnoreCase(".ENDP"))) {
            body.add(parser.declaration());
        }

        // Konsumiere das .ENDP-Token, das die Schleife beendet hat.
        if (!parser.match(TokenType.DIRECTIVE)) {
             parser.getDiagnostics().reportError("Unterminated .PROC block; missing .ENDP.", "Unknown", procName.line());
             return null;
        }

        ProcedureNode procNode = new ProcedureNode(procName, parameters, body);
        parser.registerProcedure(procNode);
        return procNode;
    }
}
