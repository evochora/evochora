package org.evochora.compiler.frontend.preprocessor.features.repeat;

import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Transforms the caret repetition shorthand syntax ({@code BODY^n}) into the
 * equivalent {@code .REPEAT n BODY} directive syntax.
 *
 * <p>This transformation runs before directive handlers, allowing the
 * {@link RepeatDirectiveHandler} to process both syntaxes uniformly.</p>
 *
 * <p>Examples:</p>
 * <pre>
 * NOP^3           → .REPEAT 3 NOP
 * JMPI LOOP^2     → .REPEAT 2 JMPI LOOP
 * NOP^3; JMPI END → .REPEAT 3 NOP; JMPI END
 * </pre>
 */
public class CaretSyntaxTransformer {

    /**
     * Transforms all caret syntax occurrences in the token list.
     * Modifies the list in place.
     *
     * @param tokens The token list to transform.
     */
    public static void transform(List<Token> tokens) {
        int i = 0;
        while (i < tokens.size()) {
            Token token = tokens.get(i);

            // Look for CARET followed by NUMBER
            if (token.type() == TokenType.CARET && i + 1 < tokens.size()) {
                Token nextToken = tokens.get(i + 1);
                if (nextToken.type() == TokenType.NUMBER) {
                    transformCaretAt(tokens, i);
                    // Don't increment i - the transformation may have changed indices
                    // and we want to re-check from the current position
                    continue;
                }
            }
            i++;
        }
    }

    /**
     * Transforms a single caret occurrence at the given index.
     *
     * <p>Finds the statement body by scanning backwards to the previous NEWLINE
     * (or start of tokens), skipping any label definition, then rewrites
     * {@code [BODY] [^] [n]} to {@code [.REPEAT] [n] [BODY]}.</p>
     *
     * <p>Labels (IDENTIFIER followed by COLON) are preserved and not included
     * in the repeat body, so {@code LABEL: NOP^2} becomes
     * {@code LABEL: .REPEAT 2 NOP} rather than {@code .REPEAT 2 LABEL: NOP}.</p>
     *
     * @param tokens The token list.
     * @param caretIndex The index of the CARET token.
     */
    private static void transformCaretAt(List<Token> tokens, int caretIndex) {
        Token caretToken = tokens.get(caretIndex);
        Token countToken = tokens.get(caretIndex + 1);

        // Find statement start (previous NEWLINE or index 0)
        int bodyStart = 0;
        for (int j = caretIndex - 1; j >= 0; j--) {
            if (tokens.get(j).type() == TokenType.NEWLINE) {
                bodyStart = j + 1;
                break;
            }
        }

        // Skip label if present (IDENTIFIER followed by COLON)
        // This ensures "LABEL: NOP^2" becomes "LABEL: .REPEAT 2 NOP"
        // rather than ".REPEAT 2 LABEL: NOP" which would be invalid
        if (bodyStart + 1 < caretIndex
                && tokens.get(bodyStart).type() == TokenType.IDENTIFIER
                && tokens.get(bodyStart + 1).type() == TokenType.COLON) {
            bodyStart += 2; // Skip past LABEL:
        }

        // Extract body tokens (everything between bodyStart and caretIndex)
        List<Token> bodyTokens = new ArrayList<>();
        for (int j = bodyStart; j < caretIndex; j++) {
            bodyTokens.add(tokens.get(j));
        }

        // If body is empty, this is an error - but we'll let the RepeatDirectiveHandler
        // deal with it (it will get .REPEAT n with no body)

        // Create the .REPEAT directive token
        Token repeatToken = new Token(
                TokenType.DIRECTIVE,
                ".REPEAT",
                null,
                caretToken.line(),
                caretToken.column(),
                caretToken.fileName()
        );

        // Build replacement: .REPEAT n BODY
        List<Token> replacement = new ArrayList<>();
        replacement.add(repeatToken);
        replacement.add(countToken);
        replacement.addAll(bodyTokens);

        // Remove original tokens: [bodyStart .. caretIndex+1] (inclusive)
        // That's: BODY tokens + CARET + NUMBER
        int removeCount = (caretIndex + 2) - bodyStart;
        for (int j = 0; j < removeCount; j++) {
            tokens.remove(bodyStart);
        }

        // Insert replacement at bodyStart
        tokens.addAll(bodyStart, replacement);
    }
}
