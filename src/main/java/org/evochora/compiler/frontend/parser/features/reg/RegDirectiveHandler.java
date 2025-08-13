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

        Token name = context.consume(TokenType.IDENTIFIER, "Expected an alias name after .REG.");
        Token register = context.consume(TokenType.REGISTER, "Expected a register after the alias name in .REG.");

        if (name != null && register != null) {
            // Wir müssen auf die Parser-Implementierung casten, um Zugriff auf die Alias-Tabelle zu bekommen.
            // Eine sauberere Lösung könnte ein Interface sein, aber das ist für jetzt pragmatisch.
            if (context instanceof Parser) {
                ((Parser) context).getRegisterAliasTable().put(name.text().toUpperCase(), register);
            }
        }

        // .REG erzeugt keinen eigenen Knoten im AST
        return null;
    }
}