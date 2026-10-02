package org.evochora.compiler.frontend.preprocessor;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A kind of block the preprocessor knows: the directives that open it, the one that closes it,
 * and the directives that divide it into parts. A feature registers the kind; the
 * {@link BlockReader} uses every registered kind to match the blocks of the stream, whatever
 * feature they belong to. All words are compared case-insensitively.
 * <p>
 * A <em>stored</em> block keeps its body for later and injects it elsewhere, once, many times
 * or never, as {@code .MACRO} and {@code .REPEAT} do; such a body may not hold a directive
 * registered as top level only. A block that is not stored is processed where it stands and may
 * hold anything.
 *
 * @param openers  The directives that open a block of this kind, at least one, e.g. {@code .MACRO}.
 * @param closer   The directive that closes it, e.g. {@code .ENDMACRO}.
 * @param dividers The directives that divide it, possibly none.
 * @param stored   Whether the body is stored for later rather than processed in place.
 */
public record BlockKind(Set<String> openers, String closer, Set<String> dividers, boolean stored) {

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

    boolean isOpener(String text) {
        return openers.contains(text.toUpperCase(Locale.ROOT));
    }

    boolean isCloser(String text) {
        return closer.equalsIgnoreCase(text);
    }

    private static Set<String> upper(Set<String> words) {
        return words.stream().map(w -> w.toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }
}
