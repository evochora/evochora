package org.evochora.compiler.frontend.module;

import java.util.List;

/**
 * The output of Phase 0 dependency scanning.
 * Contains all modules in topological order (dependencies before dependents)
 * and their metadata.
 *
 * @param topologicalOrder Modules sorted so that every module appears after its dependencies.
 */
public record DependencyGraph(List<ModuleDescriptor> topologicalOrder) {
}
