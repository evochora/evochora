package org.evochora.compiler.api;

import java.util.List;

/**
 * The text of one file as it stands in one module placement. A file imported twice is two
 * placements and appears twice, once under each alias chain; a file brought in by text inclusion
 * appears under the placement that includes it. The pair of placement and resolved path is what
 * a {@link SourceInfo} names with its placement and file name.
 * <p>
 * Besides the text, the file carries what the preprocessor recorded about it for a source view:
 * the regions of lines it left out and the notes it attached to positions. Both are recorded per
 * macro expansion, with the expansion number a {@link SourceInfo} carries; 0 stands for the text
 * outside any expansion. Every expansion of a macro is recorded on its own, so a region or note in
 * a macro body appears once per expansion.
 *
 * @param placement    The alias chain of the placement; the main module's chain for the main
 *                     file and the files it includes, usually empty.
 * @param path         The path as the program wrote it, with its source-root prefix if it has
 *                     one; for the main file the name the compiler was given.
 * @param resolvedPath The path the file was read from, the file name of every {@link SourceInfo}
 *                     in it.
 * @param lines        The file's lines.
 * @param leftOut      The regions of lines the preprocessor left out, in the order they were
 *                     recorded.
 * @param notes        The notes at positions of the file, in the order they were recorded.
 */
public record SourceFile(String placement, String path, String resolvedPath, List<String> lines,
                         List<LeftOut> leftOut, List<Note> notes) {

    /**
     * A region of lines the preprocessor left out, owned by the directive line that decided it.
     *
     * @param expansion     The macro expansion the region was decided in, 0 outside any.
     * @param directiveLine The line of the directive that owns the region.
     * @param from          The first line of the region.
     * @param to            The last line of the region, inclusive.
     */
    public record LeftOut(int expansion, int directiveLine, int from, int to) {
    }

    /**
     * A text the preprocessor attached to a position, shown next to the token there.
     *
     * @param expansion The macro expansion the note was made in, 0 outside any.
     * @param line      The line of the position.
     * @param column    The column of the position.
     * @param text      The text of the note.
     */
    public record Note(int expansion, int line, int column, String text) {
    }

    /**
     * Makes the record immutable by copying the lists; a null list of regions or notes becomes
     * an empty one.
     */
    public SourceFile {
        lines = List.copyOf(lines);
        leftOut = leftOut != null ? List.copyOf(leftOut) : List.of();
        notes = notes != null ? List.copyOf(notes) : List.of();
    }

    /**
     * Creates the text of a file with nothing recorded about it.
     *
     * @param placement    The alias chain of the placement.
     * @param path         The path as the program wrote it.
     * @param resolvedPath The path the file was read from.
     * @param lines        The file's lines.
     */
    public SourceFile(String placement, String path, String resolvedPath, List<String> lines) {
        this(placement, path, resolvedPath, lines, List.of(), List.of());
    }

    /**
     * Returns this file with the given regions and notes in place of its own.
     *
     * @param newLeftOut The regions of lines left out.
     * @param newNotes   The notes.
     * @return A file with this text and the given records.
     */
    public SourceFile withRecords(List<LeftOut> newLeftOut, List<Note> newNotes) {
        return new SourceFile(placement, path, resolvedPath, lines, newLeftOut, newNotes);
    }
}
