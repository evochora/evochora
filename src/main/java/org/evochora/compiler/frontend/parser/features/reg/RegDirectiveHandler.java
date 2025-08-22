package org.evochora.compiler.frontend.parser.features.reg;

import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.frontend.directive.IDirectiveHandler; // Platzhalter, wird später korrigiert
import org.evochora.compiler.frontend.CompilerPhase;       // Platzhalter, wird später korrigiert
import org.evochora.compiler.frontend.parser.Parser;            // Platzhalter, wird später korrigiert
import org.evochora.compiler.frontend.parser.ParsingContext;    // Platzhalter, wird später korrigiert
import org.evochora.compiler.frontend.parser.ast.AstNode;

/**
 * Handler für die .REG-Direktive.
 * Parst einen Register-Alias und fügt ihn zur Alias-Tabelle des Parsers hinzu.
 */
public class RegDirectiveHandler implements IDirectiveHandler {

    @Override
    public CompilerPhase getPhase() {
        return CompilerPhase.PARSING;
    }

    /**
     * Parst eine .REG-Anweisung.
     * Erwartetes Format: .REG <ALIAS_NAME> <REGISTER_NAME>
     * @param context Der Kontext, der den Parser kapselt.
     * @return {@code null}, da diese Direktive keinen AST-Knoten erzeugt.
     */
    @Override
    public AstNode parse(ParsingContext context) {
        context.advance(); // .REG konsumieren

        // Alias name can be IDENTIFIER (e.g., DR_A) or REGISTER (e.g., %DR_A)
        Token name;
        if (context.check(TokenType.IDENTIFIER)) {
            name = context.advance();
        } else if (context.check(TokenType.REGISTER)) {
            name = context.advance();
        } else {
            // force error with consistent message
            name = context.consume(TokenType.IDENTIFIER, "Expected an alias name after .REG.");
        }

        // Target can be a REGISTER token or a NUMBER (interpreted as %DR<NUMBER>)
        Token register;
        if (context.check(TokenType.REGISTER)) {
            register = context.advance();
        } else if (context.check(TokenType.NUMBER)) {
            Token numTok = context.advance();
            int idx = (int) numTok.value();
            String text = "%DR" + idx;
            register = new org.evochora.compiler.frontend.lexer.Token(
                    TokenType.REGISTER,
                    text,
                    null,
                    numTok.line(),
                    numTok.column(),
                    numTok.fileName()
            );
        } else {
            register = context.consume(TokenType.REGISTER, "Expected a register after the alias name in .REG.");
        }

        if (name != null && register != null) {
            // Wir müssen auf die Parser-Implementierung casten, um Zugriff auf die Alias-Tabelle zu bekommen.
            // Eine sauberere Lösung könnte ein Interface sein, aber das ist für jetzt pragmatisch.
            ((Parser) context).addRegisterAlias(name.text(), register);
        }

        // .REG erzeugt keinen eigenen Knoten im AST
        return null;
    }
}