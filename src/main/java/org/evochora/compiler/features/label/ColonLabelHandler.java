package org.evochora.compiler.features.label;

import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.frontend.preprocessor.IPreProcessorHandler;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;

import java.util.List;

/**
 * Rewrites label syntax {@code NAME:} to {@code .LABEL NAME} in the token stream.
 * This allows the parser to handle labels via the standard statement registry
 * instead of hardcoded syntax detection.
 *
 * <p>Triggered when the preprocessor encounters a {@code :} (COLON) token.
 * Distinguishes labels from typed literals ({@code CODE:5}) by checking whether
 * the preceding IDENTIFIER is at statement position (line start, or after EXPORT).</p>
 */
public class ColonLabelHandler implements IPreProcessorHandler {

    @Override
    public void process(PreProcessor preProcessor, PreProcessorContext preProcessorContext) {
        int colonIndex = preProcessor.getCurrentIndex();

        // Must have a preceding IDENTIFIER token
        if (colonIndex == 0) {
            preProcessor.advance();
            return;
        }

        Token preceding = preProcessor.getToken(colonIndex - 1);
        if (preceding.type() != TokenType.IDENTIFIER) {
            preProcessor.advance();
            return;
        }

        // Distinguish labels from typed literals (CODE:5, STRUCTURE:1).
        // Typed literals have IDENTIFIER COLON NUMBER — the token after COLON is a NUMBER.
        // Labels have IDENTIFIER COLON followed by anything else (OPCODE, NEWLINE, IDENTIFIER, EOF, etc.)
        int afterColon = colonIndex + 1;
        if (afterColon < preProcessor.streamSize()) {
            Token next = preProcessor.getToken(afterColon);
            if (next.type() == TokenType.NUMBER) {
                // Typed literal — not a label, skip
                preProcessor.advance();
                return;
            }
        }

        // Rewrite: replace IDENTIFIER COLON with .LABEL IDENTIFIER
        Token identToken = preceding;
        Token syntheticLabel = new Token(TokenType.DIRECTIVE, ".LABEL", null,
                identToken.line(), identToken.column(), identToken.fileName());

        preProcessor.removeTokens(colonIndex - 1, 2); // remove IDENTIFIER + COLON
        preProcessor.injectTokens(List.of(syntheticLabel, identToken), 0);
    }
}
