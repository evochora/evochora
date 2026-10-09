package org.evochora.compiler.model.symbols;

/**
 * A symbol together with its fully qualified name as resolved by the symbol table.
 * The qualified name is the symbol's path: the module alias chain, the levels the symbol stands
 * in and its name (e.g., "ENERGY.HARVEST" for a label HARVEST on the module level of module
 * ENERGY, "ENERGY.SCAN.DONE" for a label DONE in its level SCAN, or just "HARVEST" if the alias
 * chain is empty and the label stands on the module level).
 *
 * @param symbol        The resolved symbol.
 * @param qualifiedName The path of the symbol: the path of the scope it is defined in, or the
 *                      alias chain on the module level, followed by its name.
 * @param scope         The name of the scope the symbol is defined in: the module level's, or the
 *                      path of the level.
 */
public record ResolvedSymbol(Symbol symbol, String qualifiedName, String scope) implements Resolution {
}
