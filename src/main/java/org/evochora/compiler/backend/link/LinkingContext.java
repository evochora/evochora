package org.evochora.compiler.backend.link;

import org.evochora.compiler.frontend.semantics.SymbolTable;
import org.evochora.compiler.isa.IInstructionSet;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Mutable context for the linking phase. Provides runtime dependencies
 * (symbol table, instruction set) to linking rules, following the same
 * pattern as IrGenContext and EmissionContext in other phases.
 */
public final class LinkingContext {

    private final SymbolTable symbolTable;
    private final IInstructionSet isa;
    private int linearAddressCursor = 0;
    private final Map<Integer, int[]> callSiteBindings = new HashMap<>();
    private final Deque<String> aliasChainStack = new ArrayDeque<>();

    /**
     * Constructs a new linking context with the given runtime dependencies.
     *
     * @param symbolTable The symbol table for resolving symbols during linking.
     * @param isa         The instruction set adapter for register resolution.
     */
    public LinkingContext(SymbolTable symbolTable, IInstructionSet isa) {
        this.symbolTable = symbolTable;
        this.isa = isa;
    }

    /**
     * @return The symbol table for resolving symbols.
     */
    public SymbolTable symbolTable() { return symbolTable; }

    /**
     * @return The instruction set adapter for register resolution.
     */
    public IInstructionSet isa() { return isa; }

    /**
     * @return The next linear address and increments the cursor.
     */
    public int nextAddress() { return linearAddressCursor++; }

    /**
     * @return The current linear address.
     */
    public int currentAddress() { return linearAddressCursor; }

    /**
     * @return The map of call site bindings.
     */
    public Map<Integer, int[]> callSiteBindings() { return callSiteBindings; }

    // --- Alias chain stack for module context tracking ---

    /**
     * Pushes an alias chain when entering an imported module.
     */
    public void pushAliasChain(String aliasChain) {
        aliasChainStack.push(aliasChain);
    }

    /**
     * Pops the alias chain when leaving an imported module.
     */
    public void popAliasChain() {
        if (!aliasChainStack.isEmpty()) {
            aliasChainStack.pop();
        }
    }

    /**
     * Returns the current alias chain, or empty string if the stack is empty.
     */
    public String currentAliasChain() {
        return aliasChainStack.isEmpty() ? "" : aliasChainStack.peek();
    }
}
