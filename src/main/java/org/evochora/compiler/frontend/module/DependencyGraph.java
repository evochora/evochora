package org.evochora.compiler.frontend.module;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The output of Phase 0 dependency scanning: every file the compilation includes.
 * Modules are the files a module directive brings in; they carry dependencies and are sorted
 * so that every module appears after the modules it depends on. Source files are the files a
 * text-inclusion directive brings in; they carry no dependencies of their own and are kept as
 * plain text under the path the directive resolved to.
 *
 * @param topologicalOrder Modules sorted so that every module appears after its dependencies.
 * @param sourceContents   Text of every source file found while scanning, keyed by resolved path.
 * @param mainPath         Path of the main module, the one file that is not included anywhere.
 */
public record DependencyGraph(List<ModuleDescriptor> topologicalOrder, Map<String, String> sourceContents,
                              String mainPath) {

    /**
     * Returns the text of every file that may be included into the main file's stream: the
     * modules other than the main module, in their topological order, followed by the source
     * files. The main file is not among them because it is the stream.
     *
     * @return The included files' text, keyed by resolved path, in a fresh map.
     */
    public Map<String, String> includedContents() {
        Map<String, String> included = new LinkedHashMap<>();
        for (ModuleDescriptor module : topologicalOrder) {
            if (!module.id().path().equals(mainPath)) {
                included.put(module.sourcePath(), module.content());
            }
        }
        included.putAll(sourceContents);
        return included;
    }
}
