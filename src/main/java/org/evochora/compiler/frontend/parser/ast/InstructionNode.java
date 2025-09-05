package org.evochora.compiler.frontend.parser.ast;

import org.evochora.compiler.frontend.lexer.Token;

import java.util.Collections;
import java.util.List;

/**
 * An AST node that represents a single machine instruction.
 *
 * @param opcode The token of the opcode (e.g., SETI).
 * @param arguments A list of AST nodes that represent the arguments of the instruction.
 * @param refArguments A list of AST nodes for REF arguments, specific to CALL.
 * @param valArguments A list of AST nodes for VAL arguments, specific to CALL.
 */
public record InstructionNode(
        Token opcode,
        List<AstNode> arguments,
        List<AstNode> refArguments,
        List<AstNode> valArguments
) implements AstNode {

    /**
     * Compact constructor to ensure lists are never null.
     */
    public InstructionNode {
        if (arguments == null) {
            arguments = Collections.emptyList();
        }
        if (refArguments == null) {
            refArguments = Collections.emptyList();
        }
        if (valArguments == null) {
            valArguments = Collections.emptyList();
        }
    }

    /**
     * Constructor for instructions that do not use REF/VAL arguments.
     * @param opcode The instruction's opcode.
     * @param arguments The instruction's arguments.
     */
    public InstructionNode(Token opcode, List<AstNode> arguments) {
        this(opcode, arguments, Collections.emptyList(), Collections.emptyList());
    }

    @Override
    public List<AstNode> getChildren() {
        // The children of an instruction are its arguments.
        return arguments;
    }
    
    @Override
    public AstNode reconstructWithChildren(List<AstNode> newChildren) {
        // Create a new InstructionNode with the new children (arguments),
        // preserving the existing ref/val arguments. This is consistent with
        // how other nodes like ProcedureNode handle reconstruction.
        return new InstructionNode(opcode, newChildren, this.refArguments, this.valArguments);
    }
}