package org.evochora.compiler.core.ast;

import org.evochora.compiler.core.Token;

/**
 * Ein AST-Knoten, der eine Label-Definition repräsentiert (z.B. "L1:").
 *
 * @param labelToken Das Token, das den Namen des Labels enthält.
 * @param statement Das Statement (typischerweise eine Instruktion), das diesem Label folgt.
 */
public record LabelNode(
        Token labelToken,
        AstNode statement
) implements AstNode {
}
