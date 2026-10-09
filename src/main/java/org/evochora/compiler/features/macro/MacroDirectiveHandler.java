package org.evochora.compiler.features.macro;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.frontend.BlockReader;
import org.evochora.compiler.frontend.preprocessor.IPreProcessorBlockHandler;
import org.evochora.compiler.frontend.preprocessor.IPreProcessorHandler;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Handles the block <code>.MACRO</code> … <code>.ENDMACRO</code>, whose extent the preprocessor
 * has read. Parses the macro's name and parameters from the opener's line, takes the body from
 * the block, creates a {@link MacroExpansionHandler} for it, and dynamically registers that
 * handler in the {@link PreProcessorContext} under the macro's name. The entire definition
 * block is then removed from the token stream.
 */
public class MacroDirectiveHandler implements IPreProcessorBlockHandler {

    /**
     * Parses a macro definition.
     * The syntax is <code>.MACRO &lt;name&gt; [&lt;param1&gt; &lt;param2&gt; ...] ... .ENDMACRO</code>.
     * @param preProcessor The preprocessor providing direct access to the token stream.
     * @param preProcessorContext The preprocessor context for registering the macro.
     * @param block The extent of the definition.
     */
    @Override
    public void process(PreProcessor preProcessor, PreProcessorContext preProcessorContext, BlockReader.Block block) {
        int startIndex = block.opener();
        preProcessor.advance(); // consume .MACRO

        Token name = preProcessor.consume(TokenType.IDENTIFIER, "Expected macro name.");

        List<Token> params = new ArrayList<>();
        while (!preProcessor.isAtEnd() && preProcessor.peek().type() != TokenType.NEWLINE) {
            params.add(preProcessor.consume(TokenType.IDENTIFIER, "Expected parameter name."));
        }
        preProcessor.consume(TokenType.NEWLINE, "Expected newline after macro definition.");

        List<Token> body = preProcessor.tokensOf(block.bodyStart(), block.closer());
        MacroExpansionHandler expansion = new MacroExpansionHandler(new MacroDefinition(name, params, body));

        // A macro name may be defined again in its module, as happens when the file that defines
        // it is sourced more than once, only with the same parameters and the same body word for
        // word; the first definition stays in force. A different definition is rejected.
        Optional<IPreProcessorHandler> existing = preProcessorContext.handlers().get(name.text());
        if (existing.isEmpty()) {
            preProcessorContext.handlers().defineInModule(name.text(), expansion);
        } else if (existing.get() instanceof MacroExpansionHandler first) {
            if (!first.definition().sameTextAs(expansion.definition())) {
                String differs = first.definition().sameParametersAs(expansion.definition())
                        ? "another body" : "other parameters";
                preProcessor.getDiagnostics().reportError(
                        "Cannot define macro '" + name.text() + "' differently at " + SourceInfo.position(name.source())
                                + ": first defined at " + SourceInfo.position(first.definedAt()) + " with " + differs + ".",
                        name.source().fileName(), name.source().lineNumber());
            }
        } else {
            preProcessor.getDiagnostics().reportError(
                    "Cannot define macro '" + name.text() + "': the name is already used by another definition.",
                    name.source().fileName(), name.source().lineNumber());
        }

        // The definition leaves nothing behind: the block goes, and the newline after .ENDMACRO with it.
        int endIndex = block.end();
        if (endIndex < preProcessor.streamSize() && preProcessor.getToken(endIndex).type() == TokenType.NEWLINE) {
            endIndex++;
        }
        preProcessor.removeTokens(startIndex, endIndex - startIndex);
    }
}
