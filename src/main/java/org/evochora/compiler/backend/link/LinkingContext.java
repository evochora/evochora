package org.evochora.compiler.backend.link;

import org.evochora.compiler.model.symbols.SymbolTable;
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
    private Map<Integer, Map<Integer, Integer>> callSiteBindings = new HashMap<>();
    private final Deque<String> aliasChainStack = new ArrayDeque<>();
    private boolean frozen = false;

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
     * Freezes the context, preventing further modifications.
     * After freeze: pushAliasChain/popAliasChain/nextAddress throw,
     * callSiteBindings returns unmodifiable view.
     */
    public void freeze() {
        this.frozen = true;
        Map<Integer, Map<Integer, Integer>> deep = new HashMap<>();
        for (var entry : callSiteBindings.entrySet()) {
            deep.put(entry.getKey(), java.util.Collections.unmodifiableMap(entry.getValue()));
        }
        this.callSiteBindings = java.util.Collections.unmodifiableMap(deep);
    }

    private void guardFrozen() {
        if (frozen) throw new IllegalStateException("LinkingContext is frozen — no modifications allowed after Phase 10");
    }

    /**
     * @return The next linear address and increments the cursor.
     */
    public int nextAddress() { guardFrozen(); return linearAddressCursor++; }

    /**
     * @return The current linear address.
     */
    public int currentAddress() { return linearAddressCursor; }

    /**
     * Returns call site bindings. Outer key: linear address of the CALL instruction.
     * Inner map: formal register ID (FDR_BASE+i or FLR_BASE+i) to source register ID.
     * Mutable before {@link #freeze()}, deeply unmodifiable after.
     *
     * @return the call site bindings map
     */
    public Map<Integer, Map<Integer, Integer>> callSiteBindings() {
        return callSiteBindings;
    }

    // --- Alias chain stack for module context tracking ---

    /**
     * Pushes an alias chain when entering an imported module.
     */
    public void pushAliasChain(String aliasChain) {
        guardFrozen();
        aliasChainStack.push(aliasChain);
    }

    /**
     * Pops the alias chain when leaving an imported module.
     */
    public void popAliasChain() {
        guardFrozen();
        if (aliasChainStack.isEmpty()) {
            throw new IllegalStateException("Cannot pop alias chain: stack is empty");
        }
        aliasChainStack.pop();
    }

    /**
     * Returns the current alias chain, or empty string if the stack is empty.
     */
    public String currentAliasChain() {
        return aliasChainStack.isEmpty() ? "" : aliasChainStack.peek();
    }
}
