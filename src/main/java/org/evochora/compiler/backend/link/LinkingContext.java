package org.evochora.compiler.backend.link;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Mutable context for the linking phase.
 */
public final class LinkingContext {

    private int linearAddressCursor = 0;
    private final Map<Integer, int[]> callSiteBindings = new HashMap<>();
    private final Deque<String> aliasChainStack = new ArrayDeque<>();


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