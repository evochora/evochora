package org.evochora.compiler.frontend.module;


import java.util.List;

/**
 * One placement of a module found by the dependency scan: the main module, or a module file at
 * one of its imports. A file imported more than once has one placement per import, each scanned
 * with the flags of its import and named by its own alias chain.
 *
 * @param aliasChain   The alias chain that identifies the placement, the same the preprocessor
 *                     gives the module's tokens at this import; the main module's chain for the root.
 * @param sourcePath   The file path or URL the module was read from.
 * @param dependencies All dependencies the scan found in this placement (imports, requires, sources).
 */
public record ModulePlacement(
        String aliasChain,
        String sourcePath,
        List<IDependencyInfo> dependencies
) {}
