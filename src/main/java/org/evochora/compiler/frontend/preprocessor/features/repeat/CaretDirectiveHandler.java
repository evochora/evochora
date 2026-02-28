package org.evochora.compiler.frontend.preprocessor.features.repeat;

import org.evochora.compiler.model.Token;
import org.evochora.compiler.model.TokenType;
import org.evochora.compiler.frontend.preprocessor.IPreProcessorDirectiveHandler;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles the {@code ^} directive, which is shorthand for {@code .REPEAT}.
 * Transforms {@code BODY^n} into {@code .REPEAT n BODY} in the token stream,
 * allowing the {@link RepeatDirectiveHandler} to process it uniformly.
 *
 * <p>The handler scans backward from the {@code ^} token to find the statement
 * body on the current line, then replaces the entire sequence with the
 * equivalent {@code .REPEAT} form.</p>
 *
 * <p>Labels are preserved and excluded from the repeat body, so
 * {@code L1: NOP^3} becomes {@code L1: .REPEAT 3 NOP}.</p>
 */
public class CaretDirectiveHandler implements IPreProcessorDirectiveHandler {

    @Override
    public void process(PreProcessor preProcessor, PreProcessorContext preProcessorContext) {
        int caretIndex = preProcessor.getCurrentIndex();
        Token caretToken = preProcessor.peek();

        preProcessor.advance(); // consume ^

        Token countToken = preProcessor.consume(TokenType.NUMBER, "Expected repeat count after ^");
        int count = (Integer) countToken.value();

        if (count < 0) {
            preProcessor.getDiagnostics().reportError(
                    "Repeat count must be non-negative, got: " + count,
                    countToken.fileName(), countToken.line());
            return;
        }

        // Scan backward to find the body start (previous NEWLINE or start of stream)
        int bodyStart = 0;
        for (int j = caretIndex - 1; j >= 0; j--) {
            if (preProcessor.getToken(j).type() == TokenType.NEWLINE) {
                bodyStart = j + 1;
                break;
            }
        }

        // Skip label if present (IDENTIFIER followed by COLON)
        if (bodyStart + 1 < caretIndex
                && preProcessor.getToken(bodyStart).type() == TokenType.IDENTIFIER
                && preProcessor.getToken(bodyStart + 1).type() == TokenType.COLON) {
            bodyStart += 2;
        }

        // Collect body tokens (everything between bodyStart and caretIndex)
        List<Token> bodyTokens = new ArrayList<>();
        for (int j = bodyStart; j < caretIndex; j++) {
            bodyTokens.add(preProcessor.getToken(j));
        }

        // Build replacement: .REPEAT n BODY
        List<Token> replacement = new ArrayList<>();
        replacement.add(new Token(TokenType.DIRECTIVE, ".REPEAT", null,
                caretToken.line(), caretToken.column(), caretToken.fileName()));
        replacement.add(countToken);
        replacement.addAll(bodyTokens);

        // Remove original tokens from bodyStart to current position (body + ^ + count)
        int endIndex = preProcessor.getCurrentIndex();
        int removeCount = endIndex - bodyStart;
        preProcessor.removeTokens(bodyStart, removeCount);

        // Inject replacement at bodyStart
        preProcessor.injectTokens(replacement, 0);
    }
}
