package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.diagnostics.ErrorRecoveryException;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Reads a block of the token stream from its opener to its closer, matching every registered
 * {@link BlockKind} on the way. Blocks nest and never overlap, close in the file they opened in,
 * and a stored body holds no directive registered as top level only. The reader checks these
 * rules on the tokens as they stand in the stream, before anything inside the block is expanded,
 * and knows nothing of what a block means; the handler of the opener decides that.
 * <p>
 * Only block words, the openers, closers and dividers, are compared by file: the tokens between
 * them may come from other files, as macro arguments and inclusion markers do.
 */
public class BlockReader {

    private final PreProcessor preProcessor;
    private final PreProcessorHandlerRegistry registry;

    /**
     * Creates the reader of one preprocessor run.
     *
     * @param preProcessor The token stream the blocks are read from, and its diagnostics.
     * @param registry     The registry holding the block kinds and the top-level-only directives.
     */
    BlockReader(PreProcessor preProcessor, PreProcessorHandlerRegistry registry) {
        this.preProcessor = preProcessor;
        this.registry = registry;
    }

    /**
     * A block as it stands in the stream.
     *
     * @param body     The tokens from the one after the opener's line end up to the closer,
     *                 the closer excluded; a newline before the closer is part of the body.
     * @param dividers The stream indices of the dividers of the opened block that stand at its
     *                 own level, not inside a nested block, in stream order.
     * @param end      The stream index just after the closer.
     */
    public record Block(List<Token> body, List<Integer> dividers, int end) {
        /**
         * Copies the lists.
         */
        public Block {
            body = List.copyOf(body);
            dividers = List.copyOf(dividers);
        }
    }

    private record Open(BlockKind kind, Token opener) {
    }

    /**
     * Reads the block whose opener stands at the given index. The opener's own line belongs to
     * the handler, which has read its operands; the reader skips it unread and begins with the
     * token after the first newline that follows the opener. Nothing is removed from the stream
     * and the position of the walk is not moved on success: taking the block out is the caller's.
     * <p>
     * An error is reported to the diagnostics and ends in an {@link ErrorRecoveryException}. When
     * the block has no matching closer, because it is not closed before the end of the input or
     * of its file, or because a closer of another block ends it first, the walk is set back to the
     * opener, so that the walk passes over the opener's line unprocessed and handles what follows
     * it in place. When the
     * block is whole but holds a word it may not hold, every such word is reported and the block
     * is removed from the stream, opener to closer.
     *
     * @param openerIndex The stream index of a token that opens a registered block kind.
     * @return The body, the dividers of the opened block and the index after its closer.
     * @throws ErrorRecoveryException if the block breaks a block rule; the error has been reported.
     * @throws IllegalArgumentException if the token at the index opens no registered block kind.
     */
    public Block read(int openerIndex) {
        Token opener = preProcessor.getToken(openerIndex);
        BlockKind kind = registry.blockKindOf(opener.text())
                .filter(k -> k.isOpener(opener.text()))
                .orElseThrow(() -> new IllegalArgumentException(opener.text() + " opens no registered block"));
        String file = opener.source().fileName();

        Deque<Open> open = new ArrayDeque<>();
        open.push(new Open(kind, opener));
        int storedDepth = kind.stored() ? 1 : 0;
        List<Integer> dividers = new ArrayList<>();
        boolean misplaced = false;

        int i = skipLine(openerIndex);
        int bodyStart = i;
        while (true) {
            if (i >= preProcessor.streamSize() || preProcessor.getToken(i).type() == TokenType.END_OF_FILE) {
                Token innermost = open.peek().opener();
                return fail(openerIndex, innermost,
                        innermost.text() + " opened at " + where(innermost) + " is not closed before the end of the input");
            }
            Token token = preProcessor.getToken(i);
            BlockKind wordKind = registry.blockKindOf(token.text()).orElse(null);
            if (wordKind != null) {
                Open top = open.peek();
                if (!Objects.equals(token.source().fileName(), file)) {
                    return fail(openerIndex, top.opener(),
                            top.opener().text() + " opened at " + where(top.opener())
                                    + " is not closed before the end of " + file);
                }
                if (wordKind.isOpener(token.text())) {
                    open.push(new Open(wordKind, token));
                    if (wordKind.stored()) storedDepth++;
                } else if (wordKind.isCloser(token.text())) {
                    if (!top.kind().equals(wordKind)) {
                        return fail(openerIndex, token, mismatch(token, "closes", wordKind, open));
                    }
                    open.pop();
                    if (wordKind.stored()) storedDepth--;
                    if (open.isEmpty()) {
                        break;
                    }
                } else if (!top.kind().equals(wordKind)) {
                    report(token, mismatch(token, "divides", wordKind, open));
                    misplaced = true;
                } else if (open.size() == 1) {
                    dividers.add(i);
                }
            } else if (storedDepth > 0 && registry.isTopLevelOnly(token.text())) {
                Token storing = innermostStored(open);
                report(token, token.text() + " may not stand inside a " + storing.text()
                        + " body; the body opened at " + where(storing));
                misplaced = true;
            }
            i++;
        }

        int end = i + 1;
        if (misplaced) {
            preProcessor.removeTokens(openerIndex, end - openerIndex);
            throw new ErrorRecoveryException("Block opened at " + where(opener) + " holds a word it may not hold");
        }
        List<Token> body = new ArrayList<>(i - bodyStart);
        for (int j = bodyStart; j < i; j++) {
            body.add(preProcessor.getToken(j));
        }
        return new Block(body, dividers, end);
    }

    /**
     * Rejects a closer or divider that the walk of the preprocessor reaches. The handler of a
     * block consumes the block's closer and dividers with it, so one the walk reaches stands
     * outside any block. Such a word is reported at its place and removed from the stream; the
     * walk continues at the token that followed it.
     *
     * @param index The stream index of a token no handler claimed.
     * @return {@code true} if the token was a stray closer or divider and has been removed.
     */
    boolean rejectStray(int index) {
        Token token = preProcessor.getToken(index);
        BlockKind kind = registry.blockKindOf(token.text()).orElse(null);
        if (kind == null || kind.isOpener(token.text())) {
            return false;
        }
        String verb = kind.isCloser(token.text()) ? "closes" : "divides";
        report(token, token.text() + " " + verb + " no open block");
        preProcessor.removeTokens(index, 1);
        return true;
    }

    private int skipLine(int openerIndex) {
        int i = openerIndex + 1;
        while (i < preProcessor.streamSize()) {
            TokenType type = preProcessor.getToken(i).type();
            if (type == TokenType.END_OF_FILE) {
                return i;
            }
            i++;
            if (type == TokenType.NEWLINE) {
                return i;
            }
        }
        return i;
    }

    private static String mismatch(Token word, String verb, BlockKind wordKind, Deque<Open> open) {
        Open top = open.peek();
        String stillOpen = top.opener().text() + " opened at " + where(top.opener()) + " is still open";
        for (Open candidate : open) {
            if (candidate.kind().equals(wordKind)) {
                return word.text() + " " + verb + " the " + candidate.opener().text() + " opened at "
                        + where(candidate.opener()) + ", but the " + stillOpen;
            }
        }
        return word.text() + " " + verb + " no open block; the " + stillOpen;
    }

    private static Token innermostStored(Deque<Open> open) {
        for (Open candidate : open) {
            if (candidate.kind().stored()) {
                return candidate.opener();
            }
        }
        throw new IllegalStateException("No stored block is open");
    }

    private Block fail(int openerIndex, Token at, String message) {
        report(at, message);
        preProcessor.seek(openerIndex);
        throw new ErrorRecoveryException(message);
    }

    private void report(Token at, String message) {
        preProcessor.getDiagnostics().reportError(message, at.source().fileName(), at.source().lineNumber());
    }

    private static String where(Token token) {
        return token.source().fileName() + ":" + token.source().lineNumber();
    }
}
