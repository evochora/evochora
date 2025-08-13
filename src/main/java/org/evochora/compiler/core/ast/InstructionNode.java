package org.evochora.compiler.core.ast;

import org.evochora.compiler.core.Token;

import java.util.List;

/**
 * Ein AST-Knoten, der eine einzelne Maschinen-Instruktion repräsentiert.
 *
 * @param opcode Das Token des Opcodes (z.B. SETI).
 * @param arguments Eine Liste von AST-Knoten, die die Argumente der Instruktion darstellen.
 */
public record InstructionNode(
        Token opcode,
        List<AstNode> arguments
) implements AstNode {
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
