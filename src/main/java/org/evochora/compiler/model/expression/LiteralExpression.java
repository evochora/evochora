package org.evochora.compiler.model.expression;

import org.evochora.compiler.api.SourceInfo;

/**
 * A value written as it is, or a value a computation has already produced.
 *
 * @param value      The value.
 * @param sourceInfo The position of the node's first token.
 */
public record LiteralExpression(Value value, SourceInfo sourceInfo) implements Expression {

    @Override
    public String text() {
        return value.text();
    }
}
