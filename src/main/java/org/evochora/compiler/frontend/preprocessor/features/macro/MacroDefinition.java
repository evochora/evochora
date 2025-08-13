package org.evochora.compiler.frontend.preprocessor.features.macro;

import org.evochora.compiler.frontend.lexer.Token;

import java.util.List;

/**
 * Eine Datenstruktur, die eine einzelne Makro-Definition für den Präprozessor speichert.
 *
 * @param name         Das Token, das den Namen des Makros enthält.
 * @param parameters   Eine Liste der formalen Parameter-Namen (als Tokens).
 * @param body         Eine Liste der Tokens, die den Körper des Makros bilden.
 */
public record MacroDefinition(
        Token name,
        List<Token> parameters,
        List<Token> body
) {
}
