package org.evochora.compiler.model.expression;

import org.evochora.compiler.api.SourceInfo;

/**
 * A name in a computation: a constant, a value, a jump target, or {@code @}, the cursor.
 *
 * @param name       The name as written, or the qualified name a phase put in its place once it
 *                   resolved it; {@code @} is never qualified.
 * @param sourceInfo The position of the name's token.
 */
public record NameExpression(String name, SourceInfo sourceInfo) implements Expression {
}
