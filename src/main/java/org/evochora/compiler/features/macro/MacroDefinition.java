package org.evochora.compiler.features.macro;

import org.evochora.compiler.model.token.Token;

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

    /**
     * Reports whether another definition has the same parameters as this one: the same names in
     * the same order, spelled alike.
     *
     * @param other The other definition.
     * @return {@code true} if the parameters are the same.
     */
    public boolean sameParametersAs(MacroDefinition other) {
        return sameText(parameters, other.parameters);
    }

    /**
     * Reports whether another definition is word for word this one: the same parameters and the
     * same body, token for token by type and text. Where the tokens stand, in which placement and
     * in which instance of injected tokens, does not count.
     *
     * @param other The other definition.
     * @return {@code true} if both definitions are written alike.
     */
    public boolean sameTextAs(MacroDefinition other) {
        return sameParametersAs(other) && sameText(body, other.body);
    }

    private static boolean sameText(List<Token> a, List<Token> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            Token x = a.get(i);
            Token y = b.get(i);
            if (x.type() != y.type() || !x.text().equals(y.text())) {
                return false;
            }
        }
        return true;
    }
}
