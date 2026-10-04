package org.evochora.compiler.api;

/**
 * The key under which the artifact files what it knows of a defined name, such as the value of a
 * constant or the register of an alias: the qualified name of the definition, and the scope it is
 * defined in when that is not the module's own. A name defined in two procedures of one module is
 * two definitions with one qualified name; the scope keeps them apart.
 * <p>
 * The key is the qualified name for a definition at module level, and the qualified name
 * followed by {@code @} and the name of the scope otherwise, e.g. {@code NAV.N@NAV.STEP}. No
 * name the program can write contains {@code @}, so a key with a scope never equals a qualified
 * name, and the scope name, itself qualified by the placement, cannot run into the name before it.
 */
public final class DefinitionKey {

    /** The name of the scope at module level, outside any procedure. */
    public static final String GLOBAL_SCOPE = "global";

    private DefinitionKey() {
    }

    /**
     * Returns the key of a definition.
     *
     * @param qualifiedName The qualified name of the definition, as the token map names a use of it.
     * @param scope         The name of the scope the definition stands in; null or
     *                      {@link #GLOBAL_SCOPE} for the module level.
     * @return The key.
     */
    public static String of(String qualifiedName, String scope) {
        return scope == null || GLOBAL_SCOPE.equals(scope) ? qualifiedName : qualifiedName + "@" + scope;
    }
}
