package org.evochora.compiler.features.conditional;

import java.util.HashMap;
import java.util.Map;

/**
 * The conditional blocks the dependency scan is inside of, kept in the scan's state slot. A
 * block opens and closes in one file, so the open blocks are counted per file: a divider or an
 * {@code .ENDDEF} belongs to a block only if one is open in the file it stands in, and the blocks
 * of a file that imports or includes another are never closed by the other's words. Only the
 * blocks whose taken branch the scan is reading are counted; a branch that is not taken is
 * consumed by {@link ConditionalScanHandler} without being counted here.
 * <p>
 * A block left open at the end of its file stays counted. The preprocessor reports it, and the
 * scan, which reports nothing about conditionals, has no reason to tidy up after it.
 */
final class ScanBlocks {

    private final Map<String, Integer> openByFile = new HashMap<>();

    /**
     * Records a block whose taken branch the scan enters.
     *
     * @param file The file the block stands in.
     */
    void open(String file) {
        openByFile.merge(file, 1, Integer::sum);
    }

    /**
     * Reports whether a block is open in a file.
     *
     * @param file The file.
     * @return {@code true} if at least one block of the file is open.
     */
    boolean isOpen(String file) {
        return openByFile.containsKey(file);
    }

    /**
     * Closes the innermost open block of a file; nothing happens if none is open.
     *
     * @param file The file.
     */
    void close(String file) {
        openByFile.computeIfPresent(file, (f, depth) -> depth > 1 ? depth - 1 : null);
    }
}
