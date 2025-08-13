package org.evochora.compiler.core.directives;

import org.evochora.compiler.core.*;
import org.evochora.compiler.core.ast.AstNode;
import org.evochora.compiler.core.phases.CompilerPhase;
import org.evochora.compiler.core.phases.ParsingContext;
import org.evochora.compiler.core.phases.PreProcessor;

import java.util.ArrayList;
import java.util.List;

public class MacroDirectiveHandler implements IDirectiveHandler {
    @Override
    public CompilerPhase getPhase() {
        return CompilerPhase.PREPROCESSING;
    }

    @Override
    public AstNode parse(ParsingContext context) {
        int startIndex = ((PreProcessor)context).getCurrentIndex();
        context.advance(); // .MACRO konsumieren

        Token name = context.consume(TokenType.IDENTIFIER, "Expected macro name.");

        List<Token> params = new ArrayList<>();
        while (!context.isAtEnd() && context.peek().type() != TokenType.NEWLINE) {
            params.add(context.consume(TokenType.IDENTIFIER, "Expected parameter name."));
        }
        context.consume(TokenType.NEWLINE, "Expected newline after macro definition.");

        List<Token> body = new ArrayList<>();
        while (!context.isAtEnd() && !(context.peek().type() == TokenType.DIRECTIVE && context.peek().text().equalsIgnoreCase(".ENDM"))) {
            body.add(context.advance());
        }
        context.consume(TokenType.DIRECTIVE, "Expected .ENDM to close macro definition.");
        context.match(TokenType.NEWLINE); // Consume the trailing newline if it exists.

        ((PreProcessor)context).registerMacro(new MacroDefinition(name, params, body));

        int endIndex = ((PreProcessor)context).getCurrentIndex();
        int count = endIndex - startIndex;

        // Entferne den gesamten .MACRO...ENDM Block
        ((PreProcessor)context).removeTokens(startIndex, count);

        return null;
    }
}
