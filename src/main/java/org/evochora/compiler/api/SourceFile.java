package org.evochora.compiler.api;

import java.util.List;

/**
 * The text of one file as one inclusion brought it into the program: the main file, a module
 * placement made by an import, or a text inclusion. A file included more than once appears once
 * per inclusion, and every entry of one file shares its text. The placement, the resolved path and
 * the instance together are what the position of a token in the entry carries in a
 * {@link SourceInfo}: its placement, its file name and its expansion.
 * <p>
 * Besides the text, the entry carries what the preprocessor recorded about it for a source view:
 * the regions of lines it left out and the notes it attached to positions. Every record carries
 * the instance of injected tokens it was made in, as the expansion of its position. A record with
 * this entry's {@link #instance()} belongs to the entry itself. A record with any other number
 * belongs to an instance of injected tokens that stands on this entry's lines without being an
 * entry of its own, as {@code expansionHomes} of the artifact names it, and holds only while a
 * position of that instance is shown. Every instance is recorded on its own, so a region or note
 * in text injected more than once appears once per instance.
 *
 * @param placement    The alias chain of the placement; the main module's chain for the main
 *                     file and the files it includes, usually empty.
 * @param path         The path as the program wrote it, with its source-root prefix if it has
 *                     one; for the main file the name the compiler was given.
 * @param resolvedPath The path the file was read from, the file name of every {@link SourceInfo}
 *                     in it.
 * @param instance     The number the positions of this inclusion carry as their expansion; 0 for
 *                     the main file and for a module placement, which the placement identifies.
 * @param includedAt   The position of the directive that made this entry; null for the main file.
 * @param lines        The file's lines.
 * @param leftOut      The regions of lines the preprocessor left out, in the order they were
 *                     recorded.
 * @param notes        The notes at positions of the file, in the order they were recorded.
 */
public record SourceFile(String placement, String path, String resolvedPath, int instance, SourceInfo includedAt,
                         List<String> lines, List<LeftOut> leftOut, List<Note> notes) {

    /**
     * A region of lines the preprocessor left out, owned by the directive line that decided it.
     *
     * @param expansion     The instance of injected tokens the region was decided in.
     * @param directiveLine The line of the directive that owns the region.
     * @param from          The first line of the region.
     * @param to            The last line of the region, inclusive.
     */
    public record LeftOut(int expansion, int directiveLine, int from, int to) {
    }

    /**
     * A text the preprocessor attached to a position, shown next to the token there.
     *
     * @param expansion The instance of injected tokens the note was made in.
     * @param line      The line of the position.
     * @param column    The column of the position.
     * @param text      The text of the note.
     */
    public record Note(int expansion, int line, int column, String text) {
    }

    /**
     * Makes the record immutable by copying the lists; a null list of regions or notes becomes
     * an empty one. An immutable list of lines is kept as given, so that the entries of one file
     * share it.
     */
    public SourceFile {
        lines = List.copyOf(lines);
        leftOut = leftOut != null ? List.copyOf(leftOut) : List.of();
        notes = notes != null ? List.copyOf(notes) : List.of();
    }

    /**
     * Creates an entry of an inclusion with nothing recorded about it.
     *
     * @param placement    The alias chain of the placement.
     * @param path         The path as the program wrote it.
     * @param resolvedPath The path the file was read from.
     * @param instance     The number the positions of the inclusion carry as their expansion.
     * @param includedAt   The position of the directive that made the entry.
     * @param lines        The file's lines.
     */
    public SourceFile(String placement, String path, String resolvedPath, int instance, SourceInfo includedAt,
                      List<String> lines) {
        this(placement, path, resolvedPath, instance, includedAt, lines, List.of(), List.of());
    }

    /**
     * Creates the entry of a main file, instance 0 and included nowhere, with nothing recorded
     * about it.
     *
     * @param placement    The alias chain of the placement.
     * @param path         The path as the compiler was given it.
     * @param resolvedPath The path the file was read from.
     * @param lines        The file's lines.
     */
    public SourceFile(String placement, String path, String resolvedPath, List<String> lines) {
        this(placement, path, resolvedPath, 0, null, lines);
    }

    /**
     * Returns this entry with the given regions and notes in place of its own.
     *
     * @param newLeftOut The regions of lines left out.
     * @param newNotes   The notes.
     * @return An entry with this text and inclusion and the given records.
     */
    public SourceFile withRecords(List<LeftOut> newLeftOut, List<Note> newNotes) {
        return new SourceFile(placement, path, resolvedPath, instance, includedAt, lines, newLeftOut, newNotes);
    }
}
