package org.evochora.compiler.features.proc;

import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.model.ast.AstNode;

import org.evochora.runtime.Config;

/**
 * Handles the <code>.PREG</code> directive, which defines an alias for a procedure register (%PDR0 or %PDR1).
 * This allows using symbolic names for procedure registers, enhancing readability.
 */
public class PregDirectiveHandler implements IParserStatementHandler {

    /**
     * Parses a <code>.PREG</code> directive.
     * The syntax is <code>.PREG &lt;alias&gt; &lt;register&gt;</code>, where alias is a register token (e.g., %TMP)
     * and register is a procedure register token (e.g., %PDR0 or %PDR1).
     * @param context The parsing context.
     * @return A PregNode representing the procedure register alias.
     */
    @Override
    public AstNode parse(ParsingContext context) {
        context.advance(); // consume .PREG

        // Parse alias name (can be IDENTIFIER or REGISTER token)
        Token alias;
        if (context.check(TokenType.IDENTIFIER)) {
            alias = context.advance();
        } else if (context.check(TokenType.REGISTER)) {
            alias = context.advance();
        } else {
            alias = context.consume(TokenType.IDENTIFIER, "Expected an identifier or register alias after .PREG.");
        }

        // Parse the target procedure register (e.g., %PDR0 to %PDR{NUM_PDR_REGISTERS-1})
        Token targetRegister = context.consume(TokenType.REGISTER,
            String.format("Expected a procedure register (%%PDR0-%%PDR%d) after the alias.", Config.NUM_PDR_REGISTERS - 1));

        // Validate that it's a valid procedure register
        String registerText = targetRegister.text();
        if (!registerText.startsWith("%PDR")) {
            context.getDiagnostics().reportError(
                String.format("Expected a procedure register (%%PDR0-%%PDR%d), got: %s", Config.NUM_PDR_REGISTERS - 1, registerText),
                targetRegister.fileName(),
                targetRegister.line()
            );
            return null;
        }

        try {
            int regNum = Integer.parseInt(registerText.substring(4));
            if (regNum < 0 || regNum >= Config.NUM_PDR_REGISTERS) {
                context.getDiagnostics().reportError(
                    String.format("Procedure register '%s' is out of bounds. Valid range: %%PDR0-%%PDR%d.",
                        registerText, Config.NUM_PDR_REGISTERS - 1),
                    targetRegister.fileName(),
                    targetRegister.line()
                );
                return null;
            }
        } catch (NumberFormatException e) {
            context.getDiagnostics().reportError(
                String.format("Invalid procedure register format '%s'.", registerText),
                targetRegister.fileName(),
                targetRegister.line()
            );
            return null;
        }

        return new PregNode(alias.toSourceInfo(), alias.text(), targetRegister.text());
    }
}
