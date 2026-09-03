package org.evochora.compiler.features.macro;

import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.frontend.preprocessor.IPreProcessorHandler;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Handles the <code>.MACRO</code> and <code>.ENDM</code> directives.
 * Parses a macro definition, creates a {@link MacroExpansionHandler} for it, and
 * dynamically registers that handler in the {@link PreProcessorContext} under the
 * macro's name. The entire definition block is then removed from the token stream.
 */
public class MacroDirectiveHandler implements IPreProcessorHandler {

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

        MacroExpansionHandler expansion = new MacroExpansionHandler(new MacroDefinition(name, params, body));

        // A macro name is defined once per compilation. The same definition may arrive again,
        // when the file holding it is included a second time; any other definition of the
        // name is rejected, and the first one stays in force.
        Optional<IPreProcessorHandler> existing = preProcessorContext.getDynamicHandler(name.text());
        if (existing.isPresent() && !existing.get().equals(expansion)) {
            String firstDefinition = existing.get() instanceof MacroExpansionHandler first
                    ? first.definedAt().fileName() + ":" + first.definedAt().lineNumber()
                    : "another definition";
            preProcessor.getDiagnostics().reportError(
                    "Macro '" + name.text() + "' is already defined in " + firstDefinition,
                    name.fileName(), name.line());
        } else {
            preProcessorContext.registerDynamicHandler(name.text(), expansion);
        }

        int endIndex = preProcessor.getCurrentIndex();
        // Remove the entire .MACRO...ENDM block
        preProcessor.removeTokens(startIndex, endIndex - startIndex);
    }
}
