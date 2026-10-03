package org.evochora.compiler.model.symbols;

/**
 * A symbol together with its fully qualified name as resolved by the symbol table.
 * The qualified name incorporates the module alias chain (e.g., "ENERGY.HARVEST"
 * for a label HARVEST in module ENERGY, or just "HARVEST" if the alias chain is empty).
 *
 * @param symbol        The resolved symbol.
 * @param qualifiedName The fully qualified name (aliasChain + "." + name, or just name if root module).
 * @param scope         The name of the scope the symbol is defined in: the module level's, or the
 *                      qualified name of a procedure.
 */
public record ResolvedSymbol(Symbol symbol, String qualifiedName, String scope) implements Resolution {
}
