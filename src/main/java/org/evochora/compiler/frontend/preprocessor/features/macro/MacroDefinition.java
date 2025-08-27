package org.evochora.compiler.frontend.preprocessor.features.macro;

import org.evochora.compiler.frontend.lexer.Token;

import java.util.List;

/**
 * A data structure that stores a single macro definition for the preprocessor.
 *
 * @param name       The token containing the name of the macro.
 * @param parameters A list of the formal parameter names (as tokens).
 * @param body       A list of tokens that make up the body of the macro.
 */
public record MacroDefinition(
        Token name,
        List<Token> parameters,
        List<Token> body
) {
}
