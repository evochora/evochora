package org.evochora.compiler.frontend.parser.features.reg;

import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.frontend.directive.IDirectiveHandler;
import org.evochora.compiler.frontend.CompilerPhase;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.features.reg.RegNode;

/**
 * Handler for the .REG directive.
 * Parses a register alias and adds it to the parser's alias table.
 */
public class RegDirectiveHandler implements IDirectiveHandler {

    @Override
    public CompilerPhase getPhase() {
        return CompilerPhase.PARSING;
    }

    /**
     * Parses a .REG directive.
     * Expected format: .REG <ALIAS_NAME> <REGISTER_NAME>
     * @param context The context that encapsulates the parser.
     * @return {@code null} because this directive does not produce an AST node.
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
            // We have to cast to the Parser implementation to get access to the alias table.
            // A cleaner solution might be an interface, but this is pragmatic for now.
            ((Parser) context).addRegisterAlias(name.text(), register);
            
            // Create and return a RegNode for the AST
            return new RegNode(name, register);
        }

        // If we couldn't create a valid RegNode, return null
        return null;
    }
}