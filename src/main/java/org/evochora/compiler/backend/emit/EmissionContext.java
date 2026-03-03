package org.evochora.compiler.backend.emit;

import org.evochora.compiler.api.ParamInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable context populated by {@link IEmissionContributor}s during the emission pass.
 *
 * <p>Contributors extract feature-specific metadata from IR directives and store it here.
 * The {@link Emitter} reads the accumulated metadata when building the final
 * {@link org.evochora.compiler.api.ProgramArtifact}.</p>
 */
public final class EmissionContext {

    private final Map<String, List<ParamInfo>> procNameToParamNames = new HashMap<>();

    /**
     * Registers a procedure's parameter metadata.
     *
     * @param qualifiedName The module-qualified procedure name.
     * @param params        The procedure's parameter information.
     */
    public void registerProcedure(String qualifiedName, List<ParamInfo> params) {
        procNameToParamNames.put(qualifiedName, params);
    }

    /**
     * Returns the accumulated procedure parameter metadata.
     */
    public Map<String, List<ParamInfo>> procNameToParamNames() {
        return procNameToParamNames;
    }
}
