package org.evochora.compiler.frontend.module;

import org.evochora.compiler.api.SourceFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The output of Phase 0 dependency scanning: every module placement and every file the
 * compilation includes. A placement is the main module or a module file at one of its imports;
 * placements carry dependencies and are listed so that every placement appears after the
 * placements it imports. Source files are the files a text-inclusion directive brings in; they
 * carry no dependencies of their own and are kept as plain text under the path the directive
 * resolved to.
 *
 * @param placements     Module placements, each after the placements it imports; the main module is last.
 * @param sourceFiles    The text of every file once per placement it stands in: the main file
 *                       first, then every placement's file after the placement that imports it,
 *                       and a text-included file at its directive under the placement that
 *                       includes it.
 * @param moduleContents Text of every module file other than the main file, once per file, keyed by resolved path.
 * @param sourceContents Text of every source file found while scanning, keyed by resolved path.
 * @param mainPath       Path of the main module, the one file that is not included anywhere.
 */
public record DependencyGraph(List<ModulePlacement> placements, List<SourceFile> sourceFiles,
                              Map<String, String> moduleContents, Map<String, String> sourceContents,
                              String mainPath) {

    /**
     * Returns the text of every file that may be included into the main file's stream: the
     * module files other than the main file, followed by the source files. The main file is not
     * among them because it is the stream.
     *
     * @return The included files' text, keyed by resolved path, in a fresh map.
     */
    public Map<String, String> includedContents() {
        Map<String, String> included = new LinkedHashMap<>(moduleContents);
        included.putAll(sourceContents);
        return included;
    }
}
