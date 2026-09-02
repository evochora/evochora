package org.evochora.compiler.model.ast;

import org.evochora.compiler.api.SourceInfo;

import java.util.List;
import java.util.Objects;

/**
 * An AST node that represents a single generic machine instruction.
 *
 * @param opcode The opcode mnemonic (e.g., "SETI", "NOP", "MOV").
 * @param arguments A list of AST nodes that represent the arguments of the instruction.
 * @param sourceInfo The source location of the instruction.
 */
public record InstructionNode(
        String opcode,
        List<AstNode> arguments,
        SourceInfo sourceInfo
) implements AstNode, ISourceLocatable {

    /**
     * Rejects a missing argument list, so that {@link #arguments()} and {@link #getChildren()}
     * never yield {@code null}. An instruction without arguments carries an empty list.
     *
     * @throws NullPointerException if {@code arguments} is null
     */
    public InstructionNode {
        Objects.requireNonNull(arguments, "arguments");
    }

    @Override
    public List<AstNode> getChildren() {
        return arguments;
    }

    @Override
    public AstNode reconstructWithChildren(List<AstNode> newChildren) {
        return new InstructionNode(opcode, newChildren, sourceInfo);
    }
}
