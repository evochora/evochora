package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.SourceFile;
import org.evochora.compiler.frontend.module.PlacementContext;
import org.evochora.compiler.model.token.Token;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A shared context for the preprocessor phase.
 * Contains the state that handlers read and modify while the token stream is expanded: the
 * handlers the preprocessor dispatches to, the pre-lexed token streams of the files that may
 * be included, the source files the preprocessor's records are attached to, the inclusions
 * currently open, the options of the compilation, the numbers of the instances of injected
 * tokens, and a slot in which features keep state of their own types.
 */
public class PreProcessorContext {
    private final PreProcessorHandlerRegistry handlers = new PreProcessorHandlerRegistry();
    private final String rootAliasChain;
    private final Deque<PlacementContext> inclusions = new ArrayDeque<>();
    private final Map<String, List<Token>> fileTokens;
    private final List<SourceFile> sources;
    private final CompilerOptions options;
    private final Map<Class<?>, Object> featureState = new HashMap<>();
    private int lastInstance;

    /**
     * Creates a context carrying the token streams that were pre-lexed for the files found
     * during dependency scanning. A null alias chain becomes the empty chain, a null map an
     * empty map.
     *
     * @param rootAliasChain The alias chain for the compilation root module.
     * @param fileTokens     Pre-lexed tokens of every file that may be included, keyed by
     *                       resolved absolute path. Whether an inclusion is a module or plain
     *                       text is decided by the directive that includes the file, not here.
     * @param sources        The text of every file once per placement it stands in, as the
     *                       dependency scan found it; the preprocessor returns these files with
     *                       what it recorded for each. A null list becomes an empty one.
     * @param options        The options of the compilation, offered to handlers through
     *                       {@link #options()}; must not be null.
     */
    public PreProcessorContext(String rootAliasChain, Map<String, List<Token>> fileTokens, List<SourceFile> sources,
                               CompilerOptions options) {
        this.rootAliasChain = rootAliasChain != null ? rootAliasChain : "";
        this.fileTokens = fileTokens != null ? fileTokens : Map.of();
        this.sources = sources != null ? List.copyOf(sources) : List.of();
        this.options = Objects.requireNonNull(options, "options");
    }

    /**
     * Creates a context carrying pre-lexed token streams but no source files, for preprocessing
     * whose records are not read.
     *
     * @param rootAliasChain The alias chain for the compilation root module.
     * @param fileTokens     Pre-lexed tokens of every file that may be included, keyed by
     *                       resolved absolute path.
     * @param options        The options of the compilation; must not be null.
     */
    public PreProcessorContext(String rootAliasChain, Map<String, List<Token>> fileTokens, CompilerOptions options) {
        this(rootAliasChain, fileTokens, List.of(), options);
    }

    /**
     * Creates a context for preprocessing a single file: empty root alias chain, no files
     * that may be included, {@link CompilerOptions#defaults() default options}.
     */
    public PreProcessorContext() {
        this("", Map.of(), List.of(), CompilerOptions.defaults());
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
     * Returns the pre-lexed tokens of every file that may be included, keyed by resolved
     * absolute path.
     *
     * @return The map passed to the constructor, returned as given rather than copied; empty
     *         for a context created without such files.
     */
    public Map<String, List<Token>> fileTokens() {
        return fileTokens;
    }

    /**
     * Returns the text of every file once per placement it stands in, without records.
     *
     * @return The files passed to the constructor, in their order; empty for a context created
     *         without them.
     */
    public List<SourceFile> sources() {
        return sources;
    }

    /**
     * Returns the options of the compilation.
     *
     * @return The options passed to the constructor.
     */
    public CompilerOptions options() {
        return options;
    }

    /**
     * Returns a new instance number for a set of tokens injected together. The number is unique
     * within one preprocessor run, so that the positions of two injections of the same text stay
     * apart: a handler that injects tokens stamps them with it as the expansion of their
     * {@link org.evochora.compiler.api.SourceInfo}. The numbers count from 1; 0 stands for
     * tokens that were not given one.
     *
     * @return A number not returned before by this context, one more than the last.
     */
    public int nextInstance() {
        return ++lastInstance;
    }

    // --- Feature state ---

    /**
     * Returns the state object a feature keeps under the given key type.
     *
     * @param key The class used as the key.
     * @param <T> The type of the state object.
     * @return The state object, or {@code null} if none has been created for the key.
     */
    public <T> T get(Class<T> key) {
        return key.cast(featureState.get(key));
    }

    /**
     * Returns the state object a feature keeps under the given key type, creating it with the
     * factory on the first request. One instance exists per key for the lifetime of this
     * context; the core knows nothing of the type and never reads the object.
     *
     * @param key     The class used as the key.
     * @param factory Creates the state object; called only while no object exists for the key.
     * @param <T>     The type of the state object.
     * @return The existing or newly created state object.
     */
    public <T> T getOrCreate(Class<T> key, Supplier<T> factory) {
        return key.cast(featureState.computeIfAbsent(key, k -> factory.get()));
    }

    // --- Inclusions ---

    /**
     * Records that the tokens of a file are being inlined at the current position. The
     * inclusion stays open until {@link #leaveInclusion()} is called, so that a file which
     * includes itself, directly or through other files, is recognised.
     * <p>
     * An inclusion that carries an alias chain enters a module: names inside it are qualified
     * by that chain until the inclusion is left, and the handlers it defines, its macros, are
     * its own. An inclusion without one keeps the enclosing module context and defines into it.
     *
     * @param inclusion The included file and the alias chain of the module it enters, if any.
     */
    public void enterInclusion(PlacementContext inclusion) {
        inclusions.push(inclusion);
        if (inclusion.aliasChain() != null) {
            handlers.enterModule();
        }
    }

    /**
     * Closes the innermost open inclusion; if it entered a module, that module's definitions
     * go with it. Leaving with no inclusion open is ignored, so a stream whose closing marker
     * has no matching opening does not fail here.
     */
    public void leaveInclusion() {
        if (!inclusions.isEmpty()) {
            PlacementContext left = inclusions.pop();
            if (left.aliasChain() != null) {
                handlers.leaveModule();
            }
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
