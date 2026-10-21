package org.evochora.compiler.expression.parser;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.expression.Expression;

/**
 * What the {@link ExpressionParser} made of the tokens of a computation: the tree, or the error
 * for the caller to report.
 */
public sealed interface ParseResult permits ParseResult.Parsed, ParseResult.Failed {

    /**
     * The tokens form a computation.
     *
     * @param expression The tree.
     */
    record Parsed(Expression expression) implements ParseResult {
    }

    /**
     * The tokens form no computation.
     *
     * @param message One sentence, names quoted, such as {@code Unknown function 'MINN'.}
     * @param at      The position of the token at fault; {@code null} only for a list that holds
     *                no token at all.
     */
    record Failed(String message, SourceInfo at) implements ParseResult {
    }
}
