package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.model.token.Token;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A shared context for the preprocessor phase.
 * Contains global state that is modified or read by handlers during preprocessing
 * (e.g., the alias chain stack for module context tracking).
 */
public class PreProcessorContext {
    private final Deque<String> aliasChainStack = new ArrayDeque<>();
    private final Map<String, List<Token>> moduleTokens;
    private final Map<String, List<Token>> sourceTokens;
    private final Map<String, IPreProcessorHandler> dynamicHandlers = new HashMap<>();

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

    /**
     * Registers a dynamic preprocessor handler at runtime (e.g., macro expansion handlers).
     * Dynamic handlers are looked up after static registry handlers, allowing features like
     * {@code .MACRO} to define new directives during preprocessing.
     *
     * <p>Collision policy: if a handler is already registered for the same key and the new
     * handler is {@link Object#equals equal} to it, the registration is silently ignored
     * (idempotent). If the existing handler differs, an {@link IllegalStateException} is
     * thrown to prevent silent redefinition conflicts.</p>
     *
     * @param name    The token text that triggers this handler (uppercased for case-insensitive lookup).
     * @param handler The handler to register. Must implement {@code equals}/{@code hashCode}
     *                based on its semantic content.
     * @throws IllegalStateException if a different handler is already registered for this name.
     */
    public void registerDynamicHandler(String name, IPreProcessorHandler handler) {
        String key = name.toUpperCase();
        IPreProcessorHandler existing = dynamicHandlers.get(key);
        if (existing != null) {
            if (existing.equals(handler)) {
                return;
            }
            throw new IllegalStateException(
                    "Dynamic preprocessor handler conflict for '" + key + "': redefinition with different body");
        }
        dynamicHandlers.put(key, handler);
    }

    /**
     * Looks up a dynamic preprocessor handler by name.
     *
     * @param name The token text to look up (uppercased for case-insensitive lookup).
     * @return The handler if registered, empty otherwise.
     */
    public Optional<IPreProcessorHandler> getDynamicHandler(String name) {
        return Optional.ofNullable(dynamicHandlers.get(name.toUpperCase()));
    }
}
