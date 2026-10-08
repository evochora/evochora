package org.evochora.compiler.frontend;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A kind of block a phase knows: the directives that open it, the one that closes it, and the
 * directives that divide it into parts. A feature registers the kind with the phase the block
 * belongs to, the preprocessor or the parser; the {@link BlockReader} of that phase uses every
 * registered kind to match the blocks of its token list, whatever feature they belong to. All
 * words are compared case-insensitively. What a phase does with a block of the kind, such as
 * storing its body for later, is recorded by the phase's registry, not here.
 *
 * @param openers  The directives that open a block of this kind, at least one, e.g. {@code .MACRO}.
 * @param closer   The directive that closes it, e.g. {@code .ENDMACRO}.
 * @param dividers The directives that divide it, possibly none.
 */
public record BlockKind(Set<String> openers, String closer, Set<String> dividers) {

    /**
     * Validates the words and keeps them upper-cased.
     *
     * @throws IllegalArgumentException if no opener is given, or if a word is given twice.
     */
    public BlockKind {
        if (openers.isEmpty()) {
            throw new IllegalArgumentException("A block kind needs at least one opener; closer " + closer);
        }
        openers = upper(openers);
        closer = closer.toUpperCase(Locale.ROOT);
        dividers = upper(dividers);
        if (openers.contains(closer) || dividers.contains(closer)
                || openers.stream().anyMatch(dividers::contains)) {
            throw new IllegalArgumentException("Block kind closed by " + closer + " uses a word twice");
        }
    }

    /**
     * Returns every word of this kind: its openers, its closer and its dividers.
     *
     * @return The upper-cased words.
     */
    public Set<String> words() {
        return Stream.concat(Stream.concat(openers.stream(), dividers.stream()), Stream.of(closer))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Reports whether a word opens a block of this kind, ignoring case.
     *
     * @param text The word.
     * @return {@code true} if it is one of the openers.
     */
    public boolean isOpener(String text) {
        return openers.contains(text.toUpperCase(Locale.ROOT));
    }

    /**
     * Reports whether a word closes a block of this kind, ignoring case.
     *
     * @param text The word.
     * @return {@code true} if it is the closer.
     */
    public boolean isCloser(String text) {
        return closer.equalsIgnoreCase(text);
    }

    private static Set<String> upper(Set<String> words) {
        return words.stream().map(w -> w.toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }
}
