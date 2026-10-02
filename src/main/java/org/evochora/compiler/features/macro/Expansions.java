package org.evochora.compiler.features.macro;

/**
 * The numbers of the macro expansions of one preprocessor run, kept in the slot of the
 * preprocessor context. The numbers count from 1; 0 stands for the text outside any expansion.
 */
final class Expansions {

    private int last;

    /**
     * Opens a new expansion and returns its number.
     *
     * @return The number of the new expansion, one more than the last.
     */
    int next() {
        return ++last;
    }
}
