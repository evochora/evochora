package org.evochora.compiler.frontend.module;

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
 */
public record DependencyGraph(List<ModuleDescriptor> topologicalOrder, Map<String, String> sourceContents) {
}
