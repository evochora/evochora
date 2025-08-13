package org.evochora.compiler.frontend.preprocessor.features.routine;

import org.evochora.compiler.frontend.lexer.Token;
import java.util.List;

/**
 * Eine Datenstruktur, die eine einzelne Routine-Definition für den Präprozessor speichert.
 *
 * @param name         Das Token, das den Namen der Routine enthält.
 * @param parameters   Eine Liste der formalen Parameter-Namen (als Tokens).
 * @param body         Eine Liste der Tokens, die den Körper der Routine bilden.
 */
public record RoutineDefinition(
        Token name,
        List<Token> parameters,
        List<Token> body
) {
}