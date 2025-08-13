package org.evochora.compiler.core.directives;

import org.evochora.compiler.core.phases.CompilerPhase;
import org.evochora.compiler.core.phases.Parser;
import org.evochora.compiler.core.phases.ParsingContext;
import org.evochora.compiler.core.Token;
import org.evochora.compiler.core.TokenType;
import org.evochora.compiler.core.ast.AstNode;

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
            ((Parser) context).getRegisterAliasTable().put(name.text().toUpperCase(), register);
        }

        // .REG erzeugt keinen eigenen Knoten im AST
        return null;
    }
}
