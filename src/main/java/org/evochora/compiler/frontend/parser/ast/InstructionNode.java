package org.evochora.compiler.frontend.parser.ast;

import org.evochora.compiler.frontend.lexer.Token;

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
    public List<AstNode> getChildren() {
        // Die Kinder einer Instruktion sind ihre Argumente.
        return arguments;
    }
}