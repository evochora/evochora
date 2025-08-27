package org.evochora.compiler.frontend.preprocessor.features.macro;

import org.evochora.compiler.frontend.directive.IDirectiveHandler;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.CompilerPhase;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext; // NEU

import java.util.ArrayList;
import java.util.List;

/**
 * Handles the <code>.macro</code> and <code>.endm</code> directives.
 * This handler parses a macro definition and registers it with the {@link PreProcessorContext}.
 * The entire macro definition block is then removed from the token stream.
 */
public class MacroDirectiveHandler implements IDirectiveHandler {
    @Override
    public CompilerPhase getPhase() {
        return CompilerPhase.PREPROCESSING;
    }

    /**
     * This method is not used for preprocessor directives.
     * @param context The parsing context.
     * @return null.
     */
    @Override
    public AstNode parse(ParsingContext context) { return null; }

    /**
     * Parses a macro definition.
     * The syntax is <code>.macro &lt;name&gt; [&lt;param1&gt; &lt;param2&gt; ...] ... .endm</code>.
     * @param context The parsing context.
     * @param ppContext The preprocessor context for registering the macro.
     */
    @Override
    public void parse(ParsingContext context, PreProcessorContext ppContext) {
        PreProcessor preProcessor = (PreProcessor) context;
        int startIndex = preProcessor.getCurrentIndex();
        context.advance(); // consume .MACRO

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
        context.match(TokenType.NEWLINE);

        // USE THE NEW CONTEXT
        ppContext.registerMacro(new MacroDefinition(name, params, body));

        int endIndex = preProcessor.getCurrentIndex();
        // Remove the entire .MACRO...ENDM block
        preProcessor.removeTokens(startIndex, endIndex - startIndex);
    }
}