package org.evochora.compiler.frontend.preprocessor;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A shared context for the preprocessor phase.
 * Contains global state that is modified or read by handlers during preprocessing
 * (e.g., the alias chain stack for module context tracking).
 */
public class PreProcessorContext {
    private final Deque<String> aliasChainStack = new ArrayDeque<>();

    /**
     * @param rootAliasChain The alias chain for the compilation root module (e.g., "MAIN").
     *                       Empty string for the default/root module.
     */
    public PreProcessorContext(String rootAliasChain) {
        aliasChainStack.push(rootAliasChain != null ? rootAliasChain : "");
    }

    public PreProcessorContext() {
        this("");
    }

    /**
     * Returns the current import alias chain (e.g., "PRED.MATH").
     */
    public String currentAliasChain() {
        return aliasChainStack.peek();
    }

    /**
     * Pushes a new alias chain when entering an imported module.
     * @param aliasChain The full alias chain for the entered module.
     */
    public void pushAliasChain(String aliasChain) {
        aliasChainStack.push(aliasChain);
    }

    /**
     * Pops the alias chain when leaving an imported module.
     */
    public void popAliasChain() {
        if (aliasChainStack.size() > 1) {
            aliasChainStack.pop();
        }
    }
}