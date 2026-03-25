package org.evochora.compiler.frontend.tokenmap;

import org.evochora.compiler.api.TokenKind;
import org.evochora.compiler.model.symbols.Symbol;

/**
 * Maps internal {@link Symbol.Type} values to the public {@link TokenKind} API type.
 * This mapping lives at the boundary between the internal semantics phase and the public API.
 */
public final class TokenKindMapper {

    private TokenKindMapper() {}

    /**
     * Converts an internal symbol type to its public API equivalent.
     *
     * @param type The internal symbol type.
     * @return The corresponding public {@link TokenKind}.
     */
    public static TokenKind map(Symbol.Type type) {
        return switch (type) {
            case LABEL -> TokenKind.LABEL;
            case CONSTANT -> TokenKind.CONSTANT;
            case PROCEDURE -> TokenKind.PROCEDURE;
            case VARIABLE, LOCATION_VARIABLE -> TokenKind.VARIABLE;
            case ALIAS -> TokenKind.ALIAS;
        };
    }
}
