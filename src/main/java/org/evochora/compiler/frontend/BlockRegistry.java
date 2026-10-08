package org.evochora.compiler.frontend;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The block kinds a phase has registered, with the handler of each kind's openers. A phase's
 * registry holds one and asks it for the kind a word belongs to and for the handler a word
 * opens a block with; the {@link BlockReader} of the phase reads through the same lookup. A
 * word belongs to one kind only, and a kind has one handler. Words are compared
 * case-insensitively.
 *
 * @param <H> The handler type of the phase.
 */
public final class BlockRegistry<H> {

    private final Map<String, BlockKind> words = new HashMap<>();
    private final Map<String, H> handlers = new HashMap<>();

    /**
     * Registers a kind with the handler of its openers. Registering an equal kind with the same
     * handler again is ignored.
     *
     * @param kind    The openers, closer and dividers of the block.
     * @param handler The handler called for a whole block of the kind.
     * @throws IllegalStateException if one of the kind's words already belongs to a different
     *         kind, or if the kind is registered with a different handler.
     */
    public void register(BlockKind kind, H handler) {
        for (String word : kind.words()) {
            BlockKind existing = words.get(word);
            if (existing != null && !existing.equals(kind)) {
                throw new IllegalStateException(
                        "Block word '" + word + "' already belongs to the block closed by " + existing.closer());
            }
        }
        for (String opener : kind.openers()) {
            H existing = handlers.get(opener);
            if (existing != null && !existing.equals(handler)) {
                throw new IllegalStateException(
                        "Block opened by '" + opener + "' is already registered with a different handler");
            }
        }
        for (String word : kind.words()) {
            words.put(word, kind);
        }
        for (String opener : kind.openers()) {
            handlers.put(opener, handler);
        }
    }

    /**
     * Returns the kind of block a word opens, closes or divides.
     *
     * @param text The token text.
     * @return The kind, or empty if the text is no block word.
     */
    public Optional<BlockKind> kindOf(String text) {
        return Optional.ofNullable(words.get(text.toUpperCase(Locale.ROOT)));
    }

    /**
     * Returns the handler of the block a word opens.
     *
     * @param text The token text.
     * @return The handler, or empty if the text opens no registered kind of block.
     */
    public Optional<H> handlerOf(String text) {
        return Optional.ofNullable(handlers.get(text.toUpperCase(Locale.ROOT)));
    }

    /**
     * Reports whether a text opens, closes or divides a registered kind of block.
     *
     * @param text The token text.
     * @return {@code true} for a block word.
     */
    public boolean holds(String text) {
        return words.containsKey(text.toUpperCase(Locale.ROOT));
    }
}
