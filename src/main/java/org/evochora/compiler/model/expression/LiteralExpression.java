package org.evochora.compiler.model.expression;

import org.evochora.compiler.api.SourceInfo;

/**
 * A value written as it is, or a value a computation has already produced.
 *
 * @param value      The value.
 * @param sourceInfo The position of the node's first token.
 * @param end        The position right after the last token read for the node.
 */
public record LiteralExpression(Value value, SourceInfo sourceInfo, SourceInfo end) implements Expression {

    @Override
    public String text() {
        return value.text();
    }
}
