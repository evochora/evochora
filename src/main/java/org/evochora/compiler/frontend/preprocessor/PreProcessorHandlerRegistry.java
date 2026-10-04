package org.evochora.compiler.frontend.preprocessor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Registry of the preprocessing handlers, keyed by the token text that triggers them: a
 * directive name like ".SOURCE" or ".MACRO", or the name of a macro. It is the one place the
 * preprocessor looks a token up in.
 * <p>
 * Features fill it before the phase runs with handlers shared by the whole stream. Unlike the
 * registries of the other phases it also grows during the phase: a {@code .MACRO} directive
 * defines the handler that expands the macro from that point of the stream on, and such a
 * handler belongs to the module being processed. The registry keeps one scope per open
 * module; a lookup sees the innermost module's definitions and the shared handlers, never
 * the definitions of an enclosing or an enclosed module. Names are compared case-insensitively.
 * <p>
 * Besides the handlers it holds two properties of words that features register and the
 * {@link BlockReader} reads: the {@link BlockKind kinds of block} with their openers, closers and
 * dividers, and the directives that may stand only at the top level, never in a stored body.
 * Neither changes during the phase.
 */
public class PreProcessorHandlerRegistry {

    private final Map<String, IPreProcessorHandler> shared = new HashMap<>();
    private final Deque<Map<String, IPreProcessorHandler>> moduleScopes = new ArrayDeque<>();
    private final Map<String, BlockKind> blockWords = new HashMap<>();
    private final Set<String> topLevelOnly = new HashSet<>();

    /**
     * Creates a registry with no handlers, positioned in the root module.
     */
    public PreProcessorHandlerRegistry() {
        moduleScopes.push(new HashMap<>());
    }

    /**
     * Registers a handler for a token name.
     * <p>
     * Registering a handler that is {@link Object#equals equal} to the one already held under
     * the name is ignored, so the same definition may arrive more than once; a different
     * handler under a held name is a programming error. A feature that can tell the
     * programmer what is wrong, as the macro feature does for a second definition, reports a
     * diagnostic instead of getting here.
     *
     * @param name    The token text that triggers this handler (e.g., ".SOURCE", "MY_MACRO").
     * @param handler The handler for this token.
     * @throws IllegalStateException if a different handler is already registered for the name.
     */
    public void register(String name, IPreProcessorHandler handler) {
        put(shared, name, handler);
    }

    /**
     * Defines a handler for the module currently being processed. It answers lookups until
     * that module is left and is invisible outside it; the same name may be defined again in
     * another module. The conflict rule is that of {@link #register}.
     *
     * @param name    The token text that triggers this handler, the name of a macro.
     * @param handler The handler for this token.
     * @throws IllegalStateException if a different handler is already defined for the name in
     *         this module.
     */
    public void defineInModule(String name, IPreProcessorHandler handler) {
        put(moduleScopes.peek(), name, handler);
    }

    private static void put(Map<String, IPreProcessorHandler> scope, String name, IPreProcessorHandler handler) {
        String key = name.toUpperCase();
        IPreProcessorHandler existing = scope.get(key);
        if (existing != null) {
            if (existing.equals(handler)) {
                return;
            }
            throw new IllegalStateException(
                    "Preprocessor handler for '" + key + "' is already registered with a different handler");
        }
        scope.put(key, handler);
    }

    /**
     * Opens the scope of a module whose tokens are being inlined. Definitions made until the
     * matching {@link #leaveModule()} belong to that module.
     */
    public void enterModule() {
        moduleScopes.push(new HashMap<>());
    }

    /**
     * Closes the scope of the innermost module and drops its definitions. The root module's
     * scope is never closed.
     */
    public void leaveModule() {
        if (moduleScopes.size() > 1) {
            moduleScopes.pop();
        }
    }

    /**
     * Looks up the handler for a token name: the innermost module's definition if it has one,
     * otherwise the shared handler.
     * @param name The token text.
     * @return The handler, or empty if neither holds one for this name.
     */
    public Optional<IPreProcessorHandler> get(String name) {
        String key = name.toUpperCase();
        IPreProcessorHandler local = moduleScopes.peek().get(key);
        return Optional.ofNullable(local != null ? local : shared.get(key));
    }

    /**
     * Registers a kind of block. Registering an equal kind again is ignored.
     *
     * @param kind The openers, closer and dividers of the block, and whether its body is stored.
     * @throws IllegalStateException if one of its words already belongs to a different kind.
     */
    public void registerBlock(BlockKind kind) {
        for (String word : kind.words()) {
            BlockKind existing = blockWords.get(word);
            if (existing != null && !existing.equals(kind)) {
                throw new IllegalStateException(
                        "Block word '" + word + "' already belongs to the block closed by " + existing.closer());
            }
        }
        for (String word : kind.words()) {
            blockWords.put(word, kind);
        }
    }

    /**
     * Registers a directive that may stand only at the top level: never inside a stored body,
     * nor substituted into a stored body by a handler.
     *
     * @param directive The directive name, e.g. {@code .SOURCE}.
     */
    public void registerTopLevelOnly(String directive) {
        topLevelOnly.add(directive.toUpperCase(Locale.ROOT));
    }

    /**
     * Returns the kind of block a word opens, closes or divides.
     *
     * @param text The token text.
     * @return The kind, or empty if the text is no block word.
     */
    public Optional<BlockKind> blockKindOf(String text) {
        return Optional.ofNullable(blockWords.get(text.toUpperCase(Locale.ROOT)));
    }

    /**
     * Reports whether a text opens, closes or divides a registered kind of block.
     *
     * @param text The token text.
     * @return {@code true} for a block word.
     */
    public boolean isBlockWord(String text) {
        return blockWords.containsKey(text.toUpperCase(Locale.ROOT));
    }

    /**
     * Reports whether a text is a directive registered as top level only.
     *
     * @param text The token text.
     * @return {@code true} if the directive may not stand in a stored body.
     */
    public boolean isTopLevelOnly(String text) {
        return topLevelOnly.contains(text.toUpperCase(Locale.ROOT));
    }
}
