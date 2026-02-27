package org.evochora.compiler.frontend.parser.features.proc;

import org.evochora.compiler.frontend.parser.IParserDirectiveHandler;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.PregNode;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.runtime.Config;

/**
 * Handles the <code>.PREG</code> directive, which defines an alias for a procedure register (%PR0 or %PR1).
 * This allows using symbolic names for procedure registers, enhancing readability.
 */
public class PregDirectiveHandler implements IParserDirectiveHandler {

    /**
     * Parses a <code>.PREG</code> directive.
     * The syntax is <code>.PREG &lt;alias&gt; &lt;register&gt;</code>, where alias is a register token (e.g., %TMP)
     * and register is a procedure register token (e.g., %PR0 or %PR1).
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

        // Parse the target procedure register (e.g., %PR0 to %PR{NUM_PROC_REGISTERS-1})
        Token targetRegister = context.consume(TokenType.REGISTER,
            String.format("Expected a procedure register (%%PR0-%%PR%d) after the alias.", Config.NUM_PROC_REGISTERS - 1));

        // Validate that it's a valid procedure register
        String registerText = targetRegister.text();
        if (!registerText.startsWith("%PR")) {
            context.getDiagnostics().reportError(
                String.format("Expected a procedure register (%%PR0-%%PR%d), got: %s", Config.NUM_PROC_REGISTERS - 1, registerText),
                targetRegister.fileName(),
                targetRegister.line()
            );
            return null;
        }

        try {
            int regNum = Integer.parseInt(registerText.substring(3));
            if (regNum < 0 || regNum >= Config.NUM_PROC_REGISTERS) {
                context.getDiagnostics().reportError(
                    String.format("Procedure register '%s' is out of bounds. Valid range: %%PR0-%%PR%d.",
                        registerText, Config.NUM_PROC_REGISTERS - 1),
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

        ((org.evochora.compiler.frontend.parser.Parser) context).addRegisterAlias(alias.text(), targetRegister);

        SourceInfo sourceInfo = new SourceInfo(
            alias.fileName(),
            alias.line(),
            alias.column()
        );

        return new PregNode(sourceInfo, alias, targetRegister);
    }
}
