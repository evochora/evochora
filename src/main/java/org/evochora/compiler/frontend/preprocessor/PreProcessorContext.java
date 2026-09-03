package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.frontend.module.PlacementContext;
import org.evochora.compiler.model.token.Token;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A shared context for the preprocessor phase.
 * Contains the state that handlers read and modify while the token stream is expanded: the
 * pre-lexed token streams of the files that may be included, the inclusions currently open,
 * and the handlers defined during preprocessing itself.
 */
public class PreProcessorContext {
    private final String rootAliasChain;
    private final Deque<PlacementContext> inclusions = new ArrayDeque<>();
    private final Map<String, List<Token>> moduleTokens;
    private final Map<String, List<Token>> sourceTokens;
    private final Map<String, IPreProcessorHandler> dynamicHandlers = new HashMap<>();

    /**
     * Creates a context carrying the token streams that were pre-lexed for the files found
     * during dependency scanning. Null arguments are tolerated: a null alias chain becomes
     * the empty chain, null maps become empty maps.
     *
     * @param rootAliasChain The alias chain for the compilation root module.
     * @param moduleTokens   Pre-lexed module tokens keyed by resolved absolute path.
     * @param sourceTokens   Pre-lexed tokens of text inclusions keyed by resolved absolute path.
     */
    public PreProcessorContext(String rootAliasChain, Map<String, List<Token>> moduleTokens, Map<String, List<Token>> sourceTokens) {
        this.rootAliasChain = rootAliasChain != null ? rootAliasChain : "";
        this.moduleTokens = moduleTokens != null ? moduleTokens : Map.of();
        this.sourceTokens = sourceTokens != null ? sourceTokens : Map.of();
    }

    /**
     * Creates a context for preprocessing a single file: empty root alias chain, no pre-lexed
     * module or source tokens.
     */
    public PreProcessorContext() {
        this("", Map.of(), Map.of());
    }

    /**
     * Returns the pre-lexed module tokens keyed by resolved absolute path.
     *
     * @return The map passed to the constructor, returned as given rather than copied; empty
     *         for a context created without modules.
     */
    public Map<String, List<Token>> moduleTokens() {
        return moduleTokens;
    }

    /**
     * Returns the pre-lexed tokens of text inclusions keyed by resolved absolute path.
     *
     * @return The map passed to the constructor, returned as given rather than copied; empty
     *         for a context created without such files.
     */
    public Map<String, List<Token>> sourceTokens() {
        return sourceTokens;
    }

    // --- Inclusions ---

    /**
     * Records that the tokens of a file are being inlined at the current position. The
     * inclusion stays open until {@link #leaveInclusion()} is called, so that a file which
     * includes itself, directly or through other files, is recognised.
     * <p>
     * An inclusion that carries an alias chain enters a module: names inside it are qualified
     * by that chain until the inclusion is left. An inclusion without one keeps the enclosing
     * module context.
     *
     * @param inclusion The included file and the alias chain of the module it enters, if any.
     */
    public void enterInclusion(PlacementContext inclusion) {
        inclusions.push(inclusion);
    }

    /**
     * Closes the innermost open inclusion. Leaving with no inclusion open is ignored, so a
     * stream whose closing marker has no matching opening does not fail here.
     */
    public void leaveInclusion() {
        if (!inclusions.isEmpty()) {
            inclusions.pop();
        }
    }

    /**
     * Reports whether a file is currently being inlined, at any depth. Including it again
     * would inline it into itself.
     *
     * @param resolvedPath The resolved absolute path of the file.
     * @return {@code true} if an inclusion of that file is open.
     */
    public boolean isIncluding(String resolvedPath) {
        for (PlacementContext inclusion : inclusions) {
            if (inclusion.sourcePath().equals(resolvedPath)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the current import alias chain (e.g., "PRED.MATH"): the chain of the innermost
     * open inclusion that entered a module, or the root chain while no module has been entered.
     *
     * @return The chain qualifying names at the current position. Never null; the root chain
     *         may be empty.
     */
    public String currentAliasChain() {
        for (PlacementContext inclusion : inclusions) {
            if (inclusion.aliasChain() != null) {
                return inclusion.aliasChain();
            }
        }
        return rootAliasChain;
    }

    // --- Handlers defined during preprocessing ---

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
