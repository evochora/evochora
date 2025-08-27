package org.evochora.compiler.frontend.parser.features.proc;

import org.evochora.compiler.frontend.directive.IDirectiveHandler;
import org.evochora.compiler.frontend.CompilerPhase;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ast.AstNode;

/**
 * Handles the <code>.preg</code> directive, which defines an alias for a procedure register (%PR0 or %PR1).
 * This allows using symbolic names for procedure registers, enhancing readability.
 */
public class PregDirectiveHandler implements IDirectiveHandler {
    @Override public CompilerPhase getPhase() { return CompilerPhase.PARSING; }

    /**
     * Parses a <code>.preg</code> directive.
     * The syntax is <code>.preg &lt;alias&gt; &lt;index&gt;</code>, where alias is a register token (e.g., %TMP)
     * and index is a number (0 or 1).
     * @param context The parsing context.
     * @return null, as this directive only affects the parser's state by adding a register alias.
     */
    @Override public AstNode parse(ParsingContext context) {
        context.advance(); // consume .PREG
        Token alias = context.consume(TokenType.REGISTER, "Expected a register alias (e.g. %TMP) after .PREG.");
        Token index = context.consume(TokenType.NUMBER, "Expected a procedure register index (0 or 1) after the alias.");

        // Create the token for the real register (e.g., %PR0)
        String realRegName = "%PR" + index.value();
        Token realRegToken = new Token(TokenType.REGISTER, realRegName, null, alias.line(), alias.column(), alias.fileName());

        // Add the alias to the current scope
        ((Parser) context).addRegisterAlias(alias.text(), realRegToken);

        // Does not create an AST node anymore
        return null;
    }
}