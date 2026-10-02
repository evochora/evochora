package org.evochora.compiler.api;

import java.util.List;

/**
 * The text of one file as it stands in one module placement. A file imported twice is two
 * placements and appears twice, once under each alias chain; a file brought in by text inclusion
 * appears under the placement that includes it. The pair of placement and resolved path is what
 * a {@link SourceInfo} names with its placement and file name.
 *
 * @param placement    The alias chain of the placement; the main module's chain for the main
 *                     file and the files it includes, usually empty.
 * @param path         The path as the program wrote it, with its source-root prefix if it has
 *                     one; for the main file the name the compiler was given.
 * @param resolvedPath The path the file was read from, the file name of every {@link SourceInfo}
 *                     in it.
 * @param lines        The file's lines.
 */
public record SourceFile(String placement, String path, String resolvedPath, List<String> lines) {

    /**
     * Makes the record immutable by copying the lines.
     */
    public SourceFile {
        lines = List.copyOf(lines);
    }
}
