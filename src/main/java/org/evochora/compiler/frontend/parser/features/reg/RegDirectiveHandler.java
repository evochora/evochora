package org.evochora.compiler.frontend.parser.features.reg;

import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.frontend.parser.IParserDirectiveHandler;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.frontend.parser.ast.AstNode;

/**
 * Handler for the .REG directive.
 * Parses a register alias and adds it to the parser's alias table.
 */
public class RegDirectiveHandler implements IParserDirectiveHandler {

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
            register = new org.evochora.compiler.frontend.lexer.Token(
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
            ((Parser) context).addRegisterAlias(name.text(), register);
            return new RegNode(name, register);
        }

        return null;
    }
}
