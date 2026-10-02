package org.evochora.compiler.features.repeat;

import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.frontend.preprocessor.IPreProcessorHandler;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles the {@code ^} directive, which is shorthand for a {@code .REPEAT} block.
 * Transforms {@code BODY^n} into the block {@code .REPEAT n}, newline, {@code BODY}, newline,
 * {@code .ENDREPEAT} in the token stream, so that {@link RepeatDirectiveHandler} reads its body
 * like that of every other block and the body passes the same checks.
 *
 * <p>The handler scans backward from the {@code ^} token to find the statement
 * body on the current line, then replaces the entire sequence with the
 * equivalent block. The tokens it adds carry the position of the {@code ^}.</p>
 *
 * <p>Labels are preserved and excluded from the repeat body, so
 * {@code L1: NOP^3} becomes {@code L1:} followed by the block repeating {@code NOP}.</p>
 */
public class CaretDirectiveHandler implements IPreProcessorHandler {

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

        // Skip label if present (.LABEL directive followed by IDENTIFIER)
        if (bodyStart + 1 < caretIndex
                && preProcessor.getToken(bodyStart).type() == TokenType.DIRECTIVE
                && ".LABEL".equalsIgnoreCase(preProcessor.getToken(bodyStart).text())
                && preProcessor.getToken(bodyStart + 1).type() == TokenType.IDENTIFIER) {
            bodyStart += 2;
        }

        // Collect body tokens (everything between bodyStart and caretIndex)
        List<Token> bodyTokens = new ArrayList<>();
        for (int j = bodyStart; j < caretIndex; j++) {
            bodyTokens.add(preProcessor.getToken(j));
        }

        // Build replacement: .REPEAT n, NEWLINE, BODY, NEWLINE, .ENDREPEAT
        List<Token> replacement = new ArrayList<>();
        replacement.add(synthetic(TokenType.DIRECTIVE, ".REPEAT", caretToken));
        replacement.add(countToken);
        replacement.add(synthetic(TokenType.NEWLINE, ";", caretToken));
        replacement.addAll(bodyTokens);
        replacement.add(synthetic(TokenType.NEWLINE, ";", caretToken));
        replacement.add(synthetic(TokenType.DIRECTIVE, ".ENDREPEAT", caretToken));

        // Remove original tokens from bodyStart to current position (body + ^ + count)
        int endIndex = preProcessor.getCurrentIndex();
        int removeCount = endIndex - bodyStart;
        preProcessor.removeTokens(bodyStart, removeCount);

        // Inject replacement at bodyStart
        preProcessor.injectTokens(replacement, 0);
    }

    private static Token synthetic(TokenType type, String text, Token position) {
        return new Token(type, text, null, position.line(), position.column(), position.fileName(),
                position.placement());
    }
}
