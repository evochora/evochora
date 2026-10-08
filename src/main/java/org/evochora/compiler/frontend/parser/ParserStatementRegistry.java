package org.evochora.compiler.frontend.parser;

import org.evochora.compiler.frontend.BlockKind;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Registry for parser statement handlers and block kinds.
 * Maps keywords (directives like ".ORG", opcodes like "CALL", or other identifiers)
 * to their handlers, and the words of every block kind to the kind, with the handler of the
 * kind's openers. Supports exactly one default handler for unrecognized keywords. A word is
 * either a keyword or a block word, never both; the closer and the dividers of a block have no
 * handler. Words are compared case-insensitively.
 */
public class ParserStatementRegistry {

    private final Map<String, IParserStatementHandler> handlers = new HashMap<>();
    private final Map<String, BlockKind> blockWords = new HashMap<>();
    private final Map<String, IParserBlockHandler> blockHandlers = new HashMap<>();
    private IParserStatementHandler defaultHandler;

    /**
     * Registers a handler for a keyword.
     * @param keyword The keyword (e.g., ".ORG", "CALL").
     * @param handler The handler for this keyword.
     * @throws IllegalStateException if a handler is already registered for this keyword, or if
     *         the keyword is a word of a registered block kind.
     */
    public void register(String keyword, IParserStatementHandler handler) {
        String key = keyword.toUpperCase(Locale.ROOT);
        if (handlers.containsKey(key)) {
            throw new IllegalStateException("Parser statement handler already registered for keyword: " + keyword);
        }
        if (blockWords.containsKey(key)) {
            throw new IllegalStateException("'" + key + "' is a block word and takes no statement handler");
        }
        handlers.put(key, handler);
    }

    /**
     * Registers a kind of block together with the handler of its openers. The parser reads a
     * block of the kind before it calls the handler.
     *
     * @param kind    The openers, closer and dividers of the block.
     * @param handler The handler called for a whole block of the kind.
     * @throws IllegalStateException if one of its words is already a keyword or belongs to
     *         another kind.
     */
    public void registerBlock(BlockKind kind, IParserBlockHandler handler) {
        for (String word : kind.words()) {
            if (handlers.containsKey(word)) {
                throw new IllegalStateException("Block word '" + word + "' is already a statement keyword");
            }
            BlockKind existing = blockWords.get(word);
            if (existing != null && !existing.equals(kind)) {
                throw new IllegalStateException(
                        "Block word '" + word + "' already belongs to the block closed by " + existing.closer());
            }
        }
        for (String word : kind.words()) {
            blockWords.put(word, kind);
        }
        for (String opener : kind.openers()) {
            blockHandlers.put(opener, handler);
        }
    }

    /**
     * Registers the default handler for unrecognized keywords.
     * @param handler The default handler.
     * @throws IllegalStateException if a default handler is already registered.
     */
    public void registerDefault(IParserStatementHandler handler) {
        if (this.defaultHandler != null) {
            throw new IllegalStateException("Default parser statement handler already registered");
        }
        this.defaultHandler = handler;
    }

    /**
     * Looks up the handler for a keyword.
     * @param keyword The keyword.
     * @return The handler, or empty if no handler is registered for this keyword.
     */
    public Optional<IParserStatementHandler> get(String keyword) {
        return Optional.ofNullable(handlers.get(keyword.toUpperCase(Locale.ROOT)));
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
     * Returns the handler of the block a word opens.
     *
     * @param text The token text.
     * @return The handler, or empty if the text opens no registered kind of block.
     */
    public Optional<IParserBlockHandler> blockHandlerOf(String text) {
        return Optional.ofNullable(blockHandlers.get(text.toUpperCase(Locale.ROOT)));
    }

    /**
     * Returns the default handler, if registered.
     *
     * @return The handler used for keywords without their own registration, or empty if no
     *         default was registered.
     */
    public Optional<IParserStatementHandler> getDefault() {
        return Optional.ofNullable(defaultHandler);
    }
}
