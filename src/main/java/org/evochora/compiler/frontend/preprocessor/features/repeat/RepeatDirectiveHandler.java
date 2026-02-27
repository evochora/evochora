package org.evochora.compiler.frontend.preprocessor.features.repeat;

import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.frontend.preprocessor.IPreProcessorDirectiveHandler;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles the {@code .REPEAT} directive for repeating instructions or blocks.
 *
 * <p>This handler supports two modes:</p>
 * <ul>
 *   <li><b>Inline mode:</b> {@code .REPEAT n INSTRUCTION} - repeats a single statement
 *       (until the next NEWLINE/semicolon)</li>
 *   <li><b>Block mode:</b> {@code .REPEAT n; ... .ENDR} - repeats everything between
 *       the directive and {@code .ENDR} (triggered when NEWLINE follows the count)</li>
 * </ul>
 *
 * <p>Examples:</p>
 * <pre>
 * .REPEAT 3 NOP              ; expands to: NOP; NOP; NOP
 * .REPEAT 3 JMPI LABEL       ; expands to: JMPI LABEL; JMPI LABEL; JMPI LABEL
 * .REPEAT 3; NOP; JMPI LOOP; .ENDR  ; expands to: NOP; JMPI LOOP; NOP; JMPI LOOP; NOP; JMPI LOOP
 * </pre>
 */
public class RepeatDirectiveHandler implements IPreProcessorDirectiveHandler {

    /**
     * Parses a {@code .REPEAT} directive and expands the body.
     *
     * @param preProcessor        The preprocessor providing direct access to the token stream.
     * @param preProcessorContext  The preprocessor context (not used by this handler).
     */
    @Override
    public void process(PreProcessor preProcessor, PreProcessorContext preProcessorContext) {
        int startIndex = preProcessor.getCurrentIndex();

        preProcessor.advance(); // consume .REPEAT

        // Parse the repeat count
        Token countToken = preProcessor.consume(TokenType.NUMBER, "Expected repeat count after .REPEAT");
        int count = (Integer) countToken.value();

        if (count < 0) {
            preProcessor.getDiagnostics().reportError(
                    "Repeat count must be non-negative, got: " + count,
                    countToken.fileName(), countToken.line());
            return;
        }

        List<Token> body;
        boolean isBlockMode = preProcessor.check(TokenType.NEWLINE);

        if (isBlockMode) {
            // Block mode: .REPEAT n; ... .ENDR
            preProcessor.advance(); // consume the NEWLINE after count
            body = readUntilEndr(preProcessor);
        } else {
            // Inline mode: .REPEAT n INSTRUCTION(S)
            body = readUntilNewline(preProcessor);
        }

        // Build the expanded token list
        List<Token> expanded = new ArrayList<>();
        Token referenceToken = countToken; // Use for creating synthetic NEWLINE tokens

        for (int i = 0; i < count; i++) {
            // Clone the body tokens
            for (Token bodyToken : body) {
                expanded.add(bodyToken);
            }
            // Add NEWLINE between repetitions (not after the last one)
            if (i < count - 1) {
                expanded.add(createNewlineToken(referenceToken));
            }
        }

        // Calculate how many tokens to remove (from .REPEAT to end of directive)
        int endIndex = preProcessor.getCurrentIndex();
        int tokensToRemove = endIndex - startIndex;

        // Replace the directive with the expansion
        preProcessor.removeTokens(startIndex, tokensToRemove);
        if (!expanded.isEmpty()) {
            preProcessor.injectTokens(expanded, 0);
        }
    }

    /**
     * Reads tokens until the next NEWLINE (for inline mode).
     * Does NOT consume the terminating NEWLINE - it stays in the stream
     * as the separator to the next statement.
     */
    private List<Token> readUntilNewline(PreProcessor preProcessor) {
        List<Token> body = new ArrayList<>();
        while (!preProcessor.isAtEnd() && !preProcessor.check(TokenType.NEWLINE)) {
            body.add(preProcessor.advance());
        }
        return body;
    }

    /**
     * Reads tokens until {@code .ENDR} (for block mode).
     * Consumes the {@code .ENDR} directive and optional trailing NEWLINE.
     */
    private List<Token> readUntilEndr(PreProcessor preProcessor) {
        List<Token> body = new ArrayList<>();
        while (!preProcessor.isAtEnd()) {
            Token token = preProcessor.peek();
            if (token.type() == TokenType.DIRECTIVE && token.text().equalsIgnoreCase(".ENDR")) {
                break;
            }
            body.add(preProcessor.advance());
        }

        // Consume .ENDR
        if (!preProcessor.isAtEnd() && preProcessor.check(TokenType.DIRECTIVE)) {
            Token endrToken = preProcessor.peek();
            if (endrToken.text().equalsIgnoreCase(".ENDR")) {
                preProcessor.advance();
            } else {
                preProcessor.getDiagnostics().reportError(
                        "Expected .ENDR to close .REPEAT block",
                        endrToken.fileName(), endrToken.line());
            }
        } else {
            preProcessor.getDiagnostics().reportError(
                    "Unterminated .REPEAT block, expected .ENDR",
                    "<unknown>", 0);
        }

        // Consume optional trailing NEWLINE after .ENDR
        preProcessor.match(TokenType.NEWLINE);

        // Remove trailing NEWLINE from body if present (to avoid double newlines)
        if (!body.isEmpty() && body.get(body.size() - 1).type() == TokenType.NEWLINE) {
            body.remove(body.size() - 1);
        }

        return body;
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
