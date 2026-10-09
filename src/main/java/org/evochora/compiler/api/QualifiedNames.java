package org.evochora.compiler.api;

/**
 * The one rule by which the path of a name is formed: the path of the level the name is
 * defined on, a dot, and the name in upper case; on the compilation root, which has no path,
 * the name alone. The symbol table forms the paths of its scopes and of every name it resolves
 * this way, and the IR generator, which by design has no symbol table, forms the names of the
 * definitions it converts the same way, so that an artifact key and a token's qualified name
 * meet.
 */
public final class QualifiedNames {

    private QualifiedNames() {
    }

    /**
     * Joins a level's path and a name defined on that level to the name's path.
     *
     * @param levelPath The path of the level: an alias chain, the path of a level a node opened,
     *                  or empty or {@code null} for the compilation root.
     * @param name      The name as defined, in any case.
     * @return The path of the name, upper-cased.
     */
    public static String join(String levelPath, String name) {
        String key = name.toUpperCase();
        return levelPath != null && !levelPath.isEmpty() ? levelPath + "." + key : key;
    }
}
