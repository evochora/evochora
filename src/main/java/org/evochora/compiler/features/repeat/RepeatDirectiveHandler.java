package org.evochora.compiler.features.repeat;

import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.frontend.preprocessor.BlockReader;
import org.evochora.compiler.frontend.preprocessor.IPreProcessorHandler;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles the {@code .REPEAT} directive, which repeats a block.
 *
 * <p>{@code .REPEAT n} stands alone on its line and opens a block closed by {@code .ENDREPEAT};
 * everything between them is repeated {@code n} times, the repetitions separated by a newline.
 * The body is read through {@link PreProcessor#readBlock(int)}, so blocks inside it nest. A
 * single statement is repeated with the shorthand {@code X^n}, which
 * {@link CaretDirectiveHandler} rewrites into this block.</p>
 *
 * <p>Examples:</p>
 * <pre>
 * .REPEAT 2; NOP; JMPI LOOP; .ENDREPEAT  ; expands to: NOP; JMPI LOOP; NOP; JMPI LOOP
 * </pre>
 */
public class RepeatDirectiveHandler implements IPreProcessorHandler {

    /**
     * Parses a {@code .REPEAT} directive and expands its block.
     *
     * @param preProcessor        The preprocessor providing direct access to the token stream.
     * @param preProcessorContext  The preprocessor context (not used by this handler).
     */
    @Override
    public void process(PreProcessor preProcessor, PreProcessorContext preProcessorContext) {
        int startIndex = preProcessor.getCurrentIndex();

        Token repeatToken = preProcessor.peek();
        preProcessor.advance(); // consume .REPEAT

        Token countToken = preProcessor.consume(TokenType.NUMBER, "Expected repeat count after .REPEAT");
        int count = (Integer) countToken.value();

        if (!preProcessor.isAtEnd() && !preProcessor.check(TokenType.NEWLINE)) {
            rejectInlineForm(preProcessor, startIndex, repeatToken, count);
            return;
        }

        BlockReader.Block block = preProcessor.readBlock(startIndex);
        int tokensToRemove = block.end() - startIndex;

        if (count < 0) {
            preProcessor.getDiagnostics().reportError(
                    "Repeat count must be non-negative, got: " + count,
                    countToken.fileName(), countToken.line());
            preProcessor.removeTokens(startIndex, tokensToRemove);
            return;
        }

        // The newline after .ENDREPEAT stays in the stream and separates the last repetition from
        // the statement that follows, so the one before .ENDREPEAT is dropped from the body.
        List<Token> body = new ArrayList<>(block.body());
        if (!body.isEmpty() && body.get(body.size() - 1).type() == TokenType.NEWLINE) {
            body.remove(body.size() - 1);
        }

        List<Token> expanded = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            expanded.addAll(body);
            if (i < count - 1) {
                expanded.add(createNewlineToken(countToken));
            }
        }

        preProcessor.removeTokens(startIndex, tokensToRemove);
        if (!expanded.isEmpty()) {
            preProcessor.injectTokens(expanded, 0);
        }
    }

    /**
     * Reports a {@code .REPEAT} that has a statement on its own line, and removes that line up to
     * its end.
     */
    private void rejectInlineForm(PreProcessor preProcessor, int startIndex, Token repeatToken, int count) {
        StringBuilder statement = new StringBuilder();
        while (!preProcessor.isAtEnd() && !preProcessor.check(TokenType.NEWLINE)) {
            if (statement.length() > 0) statement.append(' ');
            statement.append(preProcessor.advance().text());
        }
        preProcessor.getDiagnostics().reportError(
                ".REPEAT takes only its count on its line and opens a block closed by .ENDREPEAT;"
                        + " a single statement is repeated as " + statement + "^" + count,
                repeatToken.fileName(), repeatToken.line());
        preProcessor.removeTokens(startIndex, preProcessor.getCurrentIndex() - startIndex);
    }

    /**
     * Creates a synthetic NEWLINE token based on a reference token's location.
     */
    private Token createNewlineToken(Token reference) {
        return new Token(
                TokenType.NEWLINE,
                ";",
                null,
                reference.line(),
                reference.column(),
                reference.fileName()
        );
    }
}
