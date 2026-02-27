package org.evochora.compiler.frontend.parser.ast;

import org.evochora.compiler.frontend.lexer.Token;

import java.util.ArrayList;
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
) implements AstNode, SourceLocatable {

    @Override
    public String getSourceFileName() {
        return opcode.fileName();
    }

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
        // The children of an instruction are its arguments, refArguments, and valArguments.
        List<AstNode> allChildren = new ArrayList<>();
        allChildren.addAll(arguments);
        allChildren.addAll(refArguments);
        allChildren.addAll(valArguments);
        return allChildren;
    }
    
    @Override
    public AstNode reconstructWithChildren(List<AstNode> newChildren) {
        // Split newChildren back into arguments, refArguments, and valArguments
        // The order is: arguments, then refArguments, then valArguments
        int argumentsSize = this.arguments.size();
        int refArgumentsSize = this.refArguments.size();
        int valArgumentsSize = this.valArguments.size();
        
        List<AstNode> newArguments = new ArrayList<>();
        List<AstNode> newRefArguments = new ArrayList<>();
        List<AstNode> newValArguments = new ArrayList<>();
        
        // Split the newChildren back into their respective lists
        int index = 0;
        
        // Add arguments
        for (int i = 0; i < argumentsSize && index < newChildren.size(); i++) {
            newArguments.add(newChildren.get(index++));
        }
        
        // Add refArguments
        for (int i = 0; i < refArgumentsSize && index < newChildren.size(); i++) {
            newRefArguments.add(newChildren.get(index++));
        }
        
        // Add valArguments
        for (int i = 0; i < valArgumentsSize && index < newChildren.size(); i++) {
            newValArguments.add(newChildren.get(index++));
        }
        
        return new InstructionNode(opcode, newArguments, newRefArguments, newValArguments);
    }
}