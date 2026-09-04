package org.evochora.compiler.model.ast;

/**
 * Capability of a defining node to stand in for the identifiers that refer to it.
 * <p>
 * A directive that gives a name to something, a register, a formal parameter, a constant,
 * records that name in the symbol table together with its own node. When a later phase meets
 * an identifier, it resolves the name and asks the node found for the replacement. The phase
 * needs no knowledge of what kind of definition it met; the node knows what an identifier
 * referring to it means.
 */
public interface IIdentifierBinding {

    /**
     * Returns the node that replaces an identifier referring to this definition.
     * <p>
     * The replacement may itself be an identifier, for a constant defined as another
     * constant; the caller resolves it again.
     *
     * @param reference the identifier to replace, with the text and source location as written
     * @return the node to put in the identifier's place; never null
     */
    AstNode bind(IdentifierNode reference);
}
