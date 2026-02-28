package org.evochora.compiler.frontend.preprocessor.features.macro;

import org.evochora.compiler.model.Token;
import org.evochora.compiler.model.TokenType;
import org.evochora.compiler.frontend.preprocessor.IPreProcessorDirectiveHandler;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles the <code>.MACRO</code> and <code>.ENDM</code> directives.
 * This handler parses a macro definition and registers it with the {@link PreProcessorContext}.
 * The entire macro definition block is then removed from the token stream.
 */
public class MacroDirectiveHandler implements IPreProcessorDirectiveHandler {

    /**
     * Parses a macro definition.
     * The syntax is <code>.MACRO &lt;name&gt; [&lt;param1&gt; &lt;param2&gt; ...] ... .ENDM</code>.
     * @param preProcessor The preprocessor providing direct access to the token stream.
     * @param preProcessorContext The preprocessor context for registering the macro.
     */
    @Override
    public void process(PreProcessor preProcessor, PreProcessorContext preProcessorContext) {
        int startIndex = preProcessor.getCurrentIndex();
        preProcessor.advance(); // consume .MACRO

        Token name = preProcessor.consume(TokenType.IDENTIFIER, "Expected macro name.");

        List<Token> params = new ArrayList<>();
        while (!preProcessor.isAtEnd() && preProcessor.peek().type() != TokenType.NEWLINE) {
            params.add(preProcessor.consume(TokenType.IDENTIFIER, "Expected parameter name."));
        }
        preProcessor.consume(TokenType.NEWLINE, "Expected newline after macro definition.");

        List<Token> body = new ArrayList<>();
        while (!preProcessor.isAtEnd() && !(preProcessor.peek().type() == TokenType.DIRECTIVE && preProcessor.peek().text().equalsIgnoreCase(".ENDM"))) {
            body.add(preProcessor.advance());
        }
        preProcessor.consume(TokenType.DIRECTIVE, "Expected .ENDM to close macro definition.");
        preProcessor.match(TokenType.NEWLINE);

        preProcessorContext.registerMacro(new MacroDefinition(name, params, body));

        int endIndex = preProcessor.getCurrentIndex();
        // Remove the entire .MACRO...ENDM block
        preProcessor.removeTokens(startIndex, endIndex - startIndex);
    }
}
