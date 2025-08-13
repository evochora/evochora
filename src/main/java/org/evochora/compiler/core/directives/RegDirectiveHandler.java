package org.evochora.compiler.core.directives;

import org.evochora.compiler.core.CompilerPhase;
import org.evochora.compiler.core.Parser;
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
     * @param parser Der Parser, der den Handler aufruft.
     * @return {@code null}, da diese Direktive keinen AST-Knoten erzeugt.
     */
    @Override
    public AstNode parse(Parser parser) {
        parser.advance(); // .REG konsumieren

        Token name = parser.consume(TokenType.IDENTIFIER, "Expected an alias name after .REG.");
        Token register = parser.consume(TokenType.REGISTER, "Expected a register after the alias name in .REG.");

        if (name != null && register != null) {
            parser.getRegisterAliasTable().put(name.text().toUpperCase(), register);
        }

        // .REG erzeugt keinen eigenen Knoten im AST
        return null;
    }
}
