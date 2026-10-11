package org.evochora.compiler.features.importdir;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a placement declared with {@code .REQUIRE}, kept in the placement's
 * {@link org.evochora.compiler.model.symbols.ModuleScope#state module scope}.
 * <p>
 * Filled by pass 1, read in pass 2: {@link RequireSymbolCollector} declares each requirement as
 * it reaches the directive, and {@link ImportAnalysisHandler} checks an import against the
 * requirements of the placement it creates. The record is complete after pass 1; this is a rule
 * the feature keeps, which the core cannot enforce.
 */
public final class Requirements {

    private final Map<String, String> modules = new LinkedHashMap<>();

    /**
     * Declares that the placement requires a module under an alias. The first declaration of an
     * alias stays.
     *
     * @param alias The alias the required module is known by in the placement, upper-cased.
     * @param path  The path the requirement names.
     */
    public void declareModule(String alias, String path) {
        modules.putIfAbsent(alias, path);
    }

    /**
     * Returns the modules the placement requires.
     *
     * @return The paths by alias, in the order of declaration; unmodifiable.
     */
    public Map<String, String> moduleRequirements() {
        return Collections.unmodifiableMap(modules);
    }
}
