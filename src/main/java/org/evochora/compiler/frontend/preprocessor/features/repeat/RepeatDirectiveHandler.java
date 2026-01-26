package org.evochora.compiler.frontend.preprocessor.features.repeat;

import org.evochora.compiler.frontend.CompilerPhase;
import org.evochora.compiler.frontend.directive.IDirectiveHandler;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.frontend.parser.ast.AstNode;
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
public class RepeatDirectiveHandler implements IDirectiveHandler {

    @Override
    public CompilerPhase getPhase() {
        return CompilerPhase.PREPROCESSING;
    }

    /**
     * Not used for preprocessor directives.
     * @param context The parsing context.
     * @return null.
     */
    @Override
    public AstNode parse(ParsingContext context) {
        return null;
    }

    /**
     * Parses a {@code .REPEAT} directive and expands the body.
     *
     * @param context   The parsing context for token stream access.
     * @param ppContext The preprocessor context (not used, but required by interface).
     */
    @Override
    public void parse(ParsingContext context, PreProcessorContext ppContext) {
        PreProcessor preProcessor = (PreProcessor) context;
        int startIndex = preProcessor.getCurrentIndex();

        context.advance(); // consume .REPEAT

        // Parse the repeat count
        Token countToken = context.consume(TokenType.NUMBER, "Expected repeat count after .REPEAT");
        int count = (Integer) countToken.value();

        if (count < 0) {
            context.getDiagnostics().reportError(
                    "Repeat count must be non-negative, got: " + count,
                    countToken.fileName(), countToken.line());
            return;
        }

        List<Token> body;
        boolean isBlockMode = context.check(TokenType.NEWLINE);

        if (isBlockMode) {
            // Block mode: .REPEAT n; ... .ENDR
            context.advance(); // consume the NEWLINE after count
            body = readUntilEndr(context);
        } else {
            // Inline mode: .REPEAT n INSTRUCTION(S)
            body = readUntilNewline(context);
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
    private List<Token> readUntilNewline(ParsingContext context) {
        List<Token> body = new ArrayList<>();
        while (!context.isAtEnd() && !context.check(TokenType.NEWLINE)) {
            body.add(context.advance());
        }
        // Do NOT consume the trailing NEWLINE - it's the separator to the next statement
        // The removal and re-injection handles this correctly
        return body;
    }

    /**
     * Reads tokens until {@code .ENDR} (for block mode).
     * Consumes the {@code .ENDR} directive and optional trailing NEWLINE.
     */
    private List<Token> readUntilEndr(ParsingContext context) {
        List<Token> body = new ArrayList<>();
        while (!context.isAtEnd()) {
            Token token = context.peek();
            if (token.type() == TokenType.DIRECTIVE && token.text().equalsIgnoreCase(".ENDR")) {
                break;
            }
            body.add(context.advance());
        }

        // Consume .ENDR
        if (!context.isAtEnd() && context.check(TokenType.DIRECTIVE)) {
            Token endrToken = context.peek();
            if (endrToken.text().equalsIgnoreCase(".ENDR")) {
                context.advance();
            } else {
                context.getDiagnostics().reportError(
                        "Expected .ENDR to close .REPEAT block",
                        endrToken.fileName(), endrToken.line());
            }
        } else {
            context.getDiagnostics().reportError(
                    "Unterminated .REPEAT block, expected .ENDR",
                    "<unknown>", 0);
        }

        // Consume optional trailing NEWLINE after .ENDR
        context.match(TokenType.NEWLINE);

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
