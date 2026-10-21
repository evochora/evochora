package org.evochora.compiler.model.expression;

import org.evochora.compiler.api.SourceInfo;

/**
 * A computation as the program writes it, free of syntax: a literal, a name, or an operation
 * applied to operands. The tree names operations by the name the operation registry knows them
 * under and names by their text; it never leaves the compiler. Every node carries the position
 * of its first token, so that an error can be reported at the node that failed, and the position
 * right after the last token read for it, so that a note on its value can stand behind it.
 * <p>
 * A parenthesis is no node: the node inside it begins at its own first token, after the
 * {@code (}, and ends after the {@code )}, the last token read for it. So in {@code (A + B) * 2}
 * the node {@code A + B} begins at {@code A} and ends after the {@code )}; a call and an index end
 * after their closing {@code )} or {@code ]}, any other operation where its last operand ends.
 */
public sealed interface Expression permits LiteralExpression, NameExpression, OperationExpression {

    /**
     * Returns the position of the node's first token.
     *
     * @return Where the node begins in the source.
     */
    SourceInfo sourceInfo();

    /**
     * Returns the position right after the last token read for the node, the closing
     * parentheses around it included.
     *
     * @return Where the node ends in the source.
     */
    SourceInfo end();

    /**
     * Writes the computation as source would, for the artifact and for tests: a literal by
     * {@link Value#text()}, a name as it stands in the tree, an operation in the form its name
     * suggests (see {@link OperationExpression#text()}). The text is deterministic; it does not
     * keep the spaces or the redundant parentheses of the source.
     *
     * @return The text of the computation.
     */
    String text();
}
