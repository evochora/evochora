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
    private final Map<String, Integer> registerAliasMap = new HashMap<>();

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

    /**
     * Registers a register alias mapping.
     *
     * @param qualifiedName The module-qualified alias name.
     * @param registerId    The physical register ID.
     */
    public void registerAlias(String qualifiedName, int registerId) {
        registerAliasMap.put(qualifiedName, registerId);
    }

    /**
     * Returns the accumulated register alias metadata.
     */
    public Map<String, Integer> registerAliasMap() {
        return registerAliasMap;
    }
}
