package org.evochora.compiler.frontend.parser.ast;

import org.evochora.compiler.frontend.lexer.Token;

import java.util.List;

/**
 * An AST node that represents a single machine instruction.
 *
 * @param opcode The token of the opcode (e.g., SETI).
 * @param arguments A list of AST nodes that represent the arguments of the instruction.
 */
public record InstructionNode(
        Token opcode,
        List<AstNode> arguments
) implements AstNode {

    @Override
    public List<AstNode> getChildren() {
        // The children of an instruction are its arguments.
        return arguments;
    }
}