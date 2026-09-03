package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.frontend.module.PlacementContext;
import org.evochora.compiler.model.token.Token;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * A shared context for the preprocessor phase.
 * Contains the state that handlers read and modify while the token stream is expanded: the
 * handlers the preprocessor dispatches to, the pre-lexed token streams of the files that may
 * be included, and the inclusions currently open.
 */
public class PreProcessorContext {
    private final PreProcessorHandlerRegistry handlers = new PreProcessorHandlerRegistry();
    private final String rootAliasChain;
    private final Deque<PlacementContext> inclusions = new ArrayDeque<>();
    private final Map<String, List<Token>> moduleTokens;
    private final Map<String, List<Token>> sourceTokens;

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
     * Returns the handlers the preprocessor dispatches to. The compiler fills the registry
     * from the features before the phase; a handler that defines a new name during the phase,
     * as {@code .MACRO} does, registers here too.
     *
     * @return The registry, owned by this context and used by the preprocessor for every lookup.
     */
    public PreProcessorHandlerRegistry handlers() {
        return handlers;
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

}
