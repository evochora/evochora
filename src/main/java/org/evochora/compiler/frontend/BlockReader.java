package org.evochora.compiler.frontend;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Reads the extent of a block on a token list: from its opener to its closer, matching every
 * registered {@link BlockKind} on the way with one stack, so that blocks nest and never overlap.
 * The phase that owns the token list creates one reader with the lookup of its registered kinds
 * and reads a block before the handler of the opener runs; the handler receives the
 * {@link Block} and never looks for a closer itself. The reader knows nothing of what a block
 * means.
 * <p>
 * A block whose structure is as it should be is <em>whole</em>. Every departure from the
 * structure is reported to the diagnostics and makes the block broken, and the reading goes on
 * to find where the block ends:
 * <ul>
 * <li>the closer of an enclosing block ends the inner block there, reported with both places,
 *     and the closer closes the enclosing block;</li>
 * <li>a divider of another kind, at any depth, is reported and passed over;</li>
 * <li>a closer with no block of its kind open is reported and passed over;</li>
 * <li>the end of the input ends the block, reported at every opener still open;</li>
 * <li>with the file rule on, a block word from another file than the opener's ends the block
 *     before that word, reported at the innermost opener. Only block words are compared by file:
 *     the tokens between them may come from other files, as tokens substituted into a stored
 *     body and the markers injected around an inclusion do.</li>
 * </ul>
 * A prefix word before a divider or the closer, on the same line, belongs to that word: the part
 * before the word ends before the prefix, and the block records which words carry one. The
 * parser uses this for {@code EXPORT}; a phase without prefix words passes a predicate that
 * matches nothing.
 */
public final class BlockReader {

    private final Function<String, Optional<BlockKind>> kindOf;
    private final boolean closesInOpenersFile;
    private final Predicate<Token> prefix;
    private final DiagnosticsEngine diagnostics;

    /**
     * Creates the reader of one phase.
     *
     * @param kindOf              The lookup of the phase's registered kinds by word, empty for a
     *                            word that is no block word.
     * @param closesInOpenersFile Whether every block word of a block has to stand in the file of
     *                            its opener.
     * @param prefix              Whether a token is a prefix word that belongs to the block word
     *                            after it.
     * @param diagnostics         Where the structural errors are reported.
     */
    public BlockReader(Function<String, Optional<BlockKind>> kindOf, boolean closesInOpenersFile,
                       Predicate<Token> prefix, DiagnosticsEngine diagnostics) {
        this.kindOf = kindOf;
        this.closesInOpenersFile = closesInOpenersFile;
        this.prefix = prefix;
        this.diagnostics = diagnostics;
    }

    /**
     * The extent of a block, as indices into the token list it was read from.
     *
     * @param opener    The index of the opener.
     * @param bodyStart The index of the first token after the opener's line; the line itself,
     *                  the header, belongs to the handler.
     * @param dividers  The indices of the dividers of the block that stand at its own level, not
     *                  inside a nested block, in order.
     * @param closer    The index of the closer, or, for a broken block without one, the index at
     *                  which the reading stopped.
     * @param end       The index at which the walk continues: after the closer, or at the index
     *                  the reading stopped at.
     * @param whole     {@code true} if no structural error was reported for the block.
     * @param prefixed  The indices among the dividers and the closer that carry a prefix word
     *                  directly before them.
     */
    public record Block(int opener, int bodyStart, List<Integer> dividers, int closer, int end, boolean whole,
                        Set<Integer> prefixed) {
        /**
         * Copies the collections.
         */
        public Block {
            dividers = List.copyOf(dividers);
            prefixed = Set.copyOf(prefixed);
        }

        /**
         * Returns the index at which the part before a divider or the closer ends: before the
         * word's prefix if it has one, else at the word.
         *
         * @param word The index of a divider or of the closer.
         * @return The end of the part, exclusive.
         */
        public int partEnd(int word) {
            return prefixed.contains(word) ? word - 1 : word;
        }
    }

    private record Open(BlockKind kind, Token opener) {
    }

    /**
     * Reads the block whose opener stands at the given index. Nothing in the list is changed.
     *
     * @param tokens      The token list.
     * @param openerIndex The index of a token that opens a registered block kind.
     * @return The extent of the block.
     * @throws IllegalArgumentException if the token at the index opens no registered block kind.
     */
    public Block read(List<Token> tokens, int openerIndex) {
        Token opener = tokens.get(openerIndex);
        BlockKind kind = kindOf.apply(opener.text())
                .filter(k -> k.isOpener(opener.text()))
                .orElseThrow(() -> new IllegalArgumentException(opener.text() + " opens no registered block"));
        String file = opener.source().fileName();

        Deque<Open> open = new ArrayDeque<>();
        open.push(new Open(kind, opener));
        List<Integer> dividers = new ArrayList<>();
        Set<Integer> prefixed = new HashSet<>();
        boolean whole = true;

        int i = skipLine(tokens, openerIndex);
        int bodyStart = i;
        while (true) {
            if (i >= tokens.size() || tokens.get(i).type() == TokenType.END_OF_FILE) {
                for (Open unclosed : open) {
                    report(unclosed.opener(), unclosed.opener().text() + " opened at " + where(unclosed.opener())
                            + " is not closed before the end of the input");
                }
                return new Block(openerIndex, bodyStart, dividers, i, i, false, prefixed);
            }
            Token token = tokens.get(i);
            BlockKind wordKind = kindOf.apply(token.text()).orElse(null);
            if (wordKind == null) {
                i++;
                continue;
            }
            Open top = open.peek();
            if (closesInOpenersFile && !Objects.equals(token.source().fileName(), file)) {
                report(top.opener(), top.opener().text() + " opened at " + where(top.opener())
                        + " is not closed before the end of " + file);
                return new Block(openerIndex, bodyStart, dividers, i, i, false, prefixed);
            }
            if (wordKind.isOpener(token.text())) {
                open.push(new Open(wordKind, token));
            } else if (wordKind.isCloser(token.text())) {
                if (!isOpen(open, wordKind)) {
                    report(token, mismatch(token, "closes", wordKind, open));
                    whole = false;
                } else {
                    if (!top.kind().equals(wordKind)) {
                        report(token, mismatch(token, "closes", wordKind, open));
                        whole = false;
                        while (!open.peek().kind().equals(wordKind)) {
                            open.pop();
                        }
                    }
                    open.pop();
                    if (open.isEmpty()) {
                        if (hasPrefix(tokens, i)) {
                            prefixed.add(i);
                        }
                        return new Block(openerIndex, bodyStart, dividers, i, i + 1, whole, prefixed);
                    }
                }
            } else if (!top.kind().equals(wordKind)) {
                report(token, mismatch(token, "divides", wordKind, open));
                whole = false;
            } else if (open.size() == 1) {
                dividers.add(i);
                if (hasPrefix(tokens, i)) {
                    prefixed.add(i);
                }
            }
            i++;
        }
    }

    private boolean hasPrefix(List<Token> tokens, int word) {
        Token before = tokens.get(word - 1);
        return prefix.test(before) && sameLine(before, tokens.get(word));
    }

    private static boolean isOpen(Deque<Open> open, BlockKind kind) {
        for (Open candidate : open) {
            if (candidate.kind().equals(kind)) {
                return true;
            }
        }
        return false;
    }

    private static int skipLine(List<Token> tokens, int openerIndex) {
        int i = openerIndex + 1;
        while (i < tokens.size()) {
            TokenType type = tokens.get(i).type();
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

    private static boolean sameLine(Token a, Token b) {
        return a.source().lineNumber() == b.source().lineNumber()
                && Objects.equals(a.source().fileName(), b.source().fileName());
    }

    private void report(Token at, String message) {
        diagnostics.reportError(message, at.source().fileName(), at.source().lineNumber());
    }

    private static String where(Token token) {
        return token.source().fileName() + ":" + token.source().lineNumber();
    }
}
