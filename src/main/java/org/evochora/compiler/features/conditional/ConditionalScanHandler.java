package org.evochora.compiler.features.conditional;

import org.evochora.compiler.frontend.DirectiveLine;
import org.evochora.compiler.frontend.lexer.IDependencyScanContext;
import org.evochora.compiler.frontend.lexer.IDependencyScanHandler;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

import java.util.List;
import java.util.Locale;

/**
 * Lexer-phase handler for the words of a conditional block, so that the files are found along
 * the same branches the preprocessor keeps, with the flags as the phase has met them, and no
 * file of a branch the preprocessor removes is read.
 * <ul>
 *   <li>{@code .IFDEF} / {@code .IFNDEF}: when the condition holds, the block is recorded as open
 *       and the reading continues inside the branch. When it does not, the handler takes the
 *       following lines itself, up to the next divider or {@code .ENDDEF} of the block, nested
 *       conditional blocks counted, and decides there in the same way; {@code .ELSEDEF} always
 *       holds. A line the handler takes is never seen by another scan handler, so nothing in a
 *       branch that is not taken is defined, imported or included.</li>
 *   <li>{@code .ELSEIFDEF}, {@code .ELSEIFNDEF} and {@code .ELSEDEF} reached by the phase end the
 *       branch that was taken: the handler takes the lines up to the block's {@code .ENDDEF}.</li>
 *   <li>{@code .ENDDEF} closes the open block.</li>
 * </ul>
 * A divider or {@code .ENDDEF} with no block open in its file is passed over. A condition the
 * handler cannot read, one that cannot be evaluated, and a head or divider that does not stand
 * alone on its line make the handler take the lines up to the block's {@code .ENDDEF}, so that
 * no branch of the block is read, as the preprocessor removes the whole block. A token before a divider or
 * the {@code .ENDDEF} does not stop the word from counting as one, as the preprocessor matches
 * the block by its words before it checks their lines. The handler reports nothing: the
 * preprocessor owns those messages, and an error reported here would stop the compilation
 * before them.
 */
public class ConditionalScanHandler implements IDependencyScanHandler {

    /** The words of a conditional block, which the feature registers this handler under. */
    public static final List<String> WORDS = List.of(".IFDEF", ".IFNDEF", ".ELSEIFDEF", ".ELSEIFNDEF", ".ELSEDEF", ".ENDDEF");

    private static final String END = ".ENDDEF";
    private static final String ELSE = ".ELSEDEF";

    @Override
    public void handle(DirectiveLine line, IDependencyScanContext ctx) {
        String word = wordOf(line);
        String file = ctx.sourcePath();
        ScanBlocks blocks = ctx.getOrCreate(ScanBlocks.class, ScanBlocks::new);
        if (isOpener(word)) {
            switch (decide(line, ctx)) {
                case HOLDS -> blocks.open(file);
                case FAILS -> seekBranch(ctx, blocks);
                case INVALID -> skipToEnd(ctx);
            }
        } else if (blocks.isOpen(file)) {
            if (!END.equals(word)) {
                skipToEnd(ctx);
            }
            blocks.close(file);
        }
    }

    /**
     * Takes the lines after a head whose condition failed, up to the divider whose condition
     * holds, which opens the block, or the block's end.
     */
    private static void seekBranch(IDependencyScanContext ctx, ScanBlocks blocks) {
        int depth = 0;
        List<Token> tokens;
        while ((tokens = ctx.nextLine()) != null) {
            DirectiveLine line = blockWord(tokens, ctx);
            if (line == null) {
                continue;
            }
            String word = wordOf(line);
            if (isOpener(word)) {
                depth++;
            } else if (END.equals(word)) {
                if (depth == 0) {
                    return;
                }
                depth--;
            } else if (depth == 0) {
                Decision decision = ELSE.equals(word) && line.standsAlone()
                        ? Decision.HOLDS : decide(line, ctx);
                if (decision == Decision.HOLDS) {
                    blocks.open(ctx.sourcePath());
                    return;
                }
                if (decision == Decision.INVALID) {
                    skipToEnd(ctx);
                    return;
                }
            }
        }
    }

    /**
     * Takes the lines up to the end of the block, nested blocks counted.
     */
    private static void skipToEnd(IDependencyScanContext ctx) {
        int depth = 0;
        List<Token> tokens;
        while ((tokens = ctx.nextLine()) != null) {
            DirectiveLine line = blockWord(tokens, ctx);
            if (line == null) {
                continue;
            }
            String word = wordOf(line);
            if (isOpener(word)) {
                depth++;
            } else if (END.equals(word)) {
                if (depth == 0) {
                    return;
                }
                depth--;
            }
        }
    }

    private enum Decision { HOLDS, FAILS, INVALID }

    /**
     * Decides a head. A head that does not stand alone on its line is invalid, as the
     * preprocessor finds it so; so is a condition the preprocessor would reject, and one with a
     * value that cannot be computed, which the preprocessor reports.
     */
    private static Decision decide(DirectiveLine line, IDependencyScanContext ctx) {
        if (!line.standsAlone()) {
            return Decision.INVALID;
        }
        try {
            Condition condition = Condition.parse(line.word(), line.operands());
            return condition.holds(ctx.flags(), ctx::evaluate)
                    .map(holds -> holds ? Decision.HOLDS : Decision.FAILS)
                    .orElse(Decision.INVALID);
        } catch (Condition.Invalid invalid) {
            return Decision.INVALID;
        }
    }

    /**
     * Finds the first word of a conditional block on a line, wherever it stands.
     *
     * @return The directive on its line, seen on the file's whole token stream, or {@code null}
     *         if the line holds no such word.
     */
    private static DirectiveLine blockWord(List<Token> tokens, IDependencyScanContext ctx) {
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.type() == TokenType.DIRECTIVE && WORDS.contains(token.text().toUpperCase(Locale.ROOT))) {
                return ctx.lineOf(i);
            }
        }
        return null;
    }

    private static String wordOf(DirectiveLine line) {
        return line.word().text().toUpperCase(Locale.ROOT);
    }

    private static boolean isOpener(String word) {
        return ".IFDEF".equals(word) || ".IFNDEF".equals(word);
    }
}
