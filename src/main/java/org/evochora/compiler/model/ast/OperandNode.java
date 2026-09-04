package org.evochora.compiler.model.ast;

/**
 * An expression as it may stand in operand position: what the parser produces for an
 * argument of an instruction or a directive. There are exactly five forms, fixed by the
 * language: a register, a number, a typed literal, a vector, and a name.
 * <p>
 * The forms are fixed by the core because every phase that reads an operand has to know all
 * of them, from the token map through IR generation. A feature that needs a new way to write
 * an argument parses it in its own directive handler and carries it in its own node; the
 * operand forms of instructions are the language's, not a feature's.
 */
public sealed interface OperandNode extends AstNode, ISourceLocatable
        permits RegisterNode, NumberLiteralNode, TypedLiteralNode, VectorLiteralNode, IdentifierNode {
}
