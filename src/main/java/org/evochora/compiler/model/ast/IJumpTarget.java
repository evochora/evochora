package org.evochora.compiler.model.ast;

/**
 * Capability of an AST node whose name marks a place in the program's code, such as a label
 * or a procedure. An identifier that names such a node stays a name through the frontend;
 * the linker turns it into the value the machine code carries for that place. A phase that
 * has to know whether an identifier may stand where a label or a jump vector is expected asks
 * for this capability, never for the kind of the symbol.
 */
public interface IJumpTarget {
}
