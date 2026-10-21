package org.evochora.compiler.model.expression;

import org.evochora.compiler.api.SourceInfo;

/**
 * A computation as the program writes it, free of syntax: a literal, a name, or an operation
 * applied to operands. The tree names operations by the name the operation registry knows them
 * under and names by their text; it never leaves the compiler. Every node carries the position
 * of its first token, so that an error can be reported at the node that failed.
 */
public sealed interface Expression permits LiteralExpression, NameExpression, OperationExpression {

    /**
     * Returns the position of the node's first token.
     *
     * @return Where the node begins in the source.
     */
    SourceInfo sourceInfo();
}
