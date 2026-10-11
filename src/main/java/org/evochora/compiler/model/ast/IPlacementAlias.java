package org.evochora.compiler.model.ast;

/**
 * Capability of an AST node that defines a name which is an alias of the module level. The alias
 * leads into another placement when the symbol table's alias map has an entry for it. The symbol
 * table reads this capability to tell an alias that leads nowhere from a name that opened no
 * level, never the kind of the symbol.
 */
public interface IPlacementAlias {
}
