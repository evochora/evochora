package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.model.token.Token;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * A shared context for the preprocessor phase.
 * Contains global state that is modified or read by handlers during preprocessing
 * (e.g., the alias chain stack for module context tracking).
 */
public class PreProcessorContext {
    private final Deque<String> aliasChainStack = new ArrayDeque<>();
    private final Map<String, List<Token>> moduleTokens;
    private final Map<String, List<Token>> sourceTokens;

    /**
     * @param rootAliasChain The alias chain for the compilation root module.
     * @param moduleTokens   Pre-lexed .IMPORT module tokens keyed by resolved absolute path.
     * @param sourceTokens   Pre-lexed .SOURCE file tokens keyed by resolved absolute path.
     */
    public PreProcessorContext(String rootAliasChain, Map<String, List<Token>> moduleTokens, Map<String, List<Token>> sourceTokens) {
        aliasChainStack.push(rootAliasChain != null ? rootAliasChain : "");
        this.moduleTokens = moduleTokens != null ? moduleTokens : Map.of();
        this.sourceTokens = sourceTokens != null ? sourceTokens : Map.of();
    }

    public PreProcessorContext() {
        this("", Map.of(), Map.of());
    }

    /**
     * Returns the pre-lexed .IMPORT module tokens keyed by resolved absolute path.
     */
    public Map<String, List<Token>> moduleTokens() {
        return moduleTokens;
    }

    /**
     * Returns the pre-lexed .SOURCE file tokens keyed by resolved absolute path.
     */
    public Map<String, List<Token>> sourceTokens() {
        return sourceTokens;
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
