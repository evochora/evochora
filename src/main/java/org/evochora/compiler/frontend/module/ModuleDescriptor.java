package org.evochora.compiler.frontend.module;

import org.evochora.compiler.frontend.semantics.ModuleId;

import java.util.List;

/**
 * Per-module metadata from the dependency scan.
 *
 * @param id           The unique identity of this module.
 * @param sourcePath   The file path or URL.
 * @param content      The raw source text (loaded during the scan).
 * @param dependencies All dependencies found in this module (imports, requires, sources).
 */
public record ModuleDescriptor(
        ModuleId id,
        String sourcePath,
        String content,
        List<IDependencyInfo> dependencies
) {}
