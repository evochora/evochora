package org.evochora.compiler.features.reg;

import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.model.ast.AstNode;

/**
 * Handler for the .REG directive.
 * Parses a register alias and adds it to the parser's alias table.
 */
public class RegDirectiveHandler implements IParserStatementHandler {

    /**
     * Parses a .REG directive.
     * Expected format: .REG <ALIAS_NAME> <REGISTER_NAME>
     * @param context The context that encapsulates the parser.
     * @return A {@link RegNode} or {@code null} if parsing fails.
     */
    @Override
    public AstNode parse(ParsingContext context) {
        context.advance(); // consume .REG

        // Alias name can be IDENTIFIER (e.g., DR_A) or REGISTER (e.g., %DR_A)
        Token name;
        if (context.check(TokenType.IDENTIFIER)) {
            name = context.advance();
        } else if (context.check(TokenType.REGISTER)) {
            name = context.advance();
        } else {
            // force error with consistent message
            name = context.consume(TokenType.IDENTIFIER, "Expected an alias name after .REG.");
        }

        // Target can be a REGISTER token or a NUMBER (interpreted as %DR<NUMBER>)
        Token register;
        if (context.check(TokenType.REGISTER)) {
            register = context.advance();
        } else if (context.check(TokenType.NUMBER)) {
            Token numTok = context.advance();
            int idx = (int) numTok.value();
            String text = "%DR" + idx;
            register = new Token(
                    TokenType.REGISTER,
                    text,
                    null,
                    numTok.line(),
                    numTok.column(),
                    numTok.fileName()
            );
        } else {
            register = context.consume(TokenType.REGISTER, "Expected a register after the alias name in .REG.");
        }

        if (name != null && register != null) {
            return new RegNode(name.text(), register.text(), name.toSourceInfo());
        }

        return null;
    }
}
