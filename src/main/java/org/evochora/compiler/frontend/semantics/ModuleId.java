package org.evochora.compiler.frontend.semantics;

/**
 * Identifies a module by its resolved file path or URL.
 * Used as a map key in the module-aware symbol table.
 *
 * @param path The resolved, normalized file path or URL that uniquely identifies a module.
 */
public record ModuleId(String path) {

    /**
     * Derives a short, human-readable module name from a file path or HTTP URL.
     * Extracts the last path segment, strips the file extension, and uppercases.
     * Examples: {@code /home/user/lib/energy.evo} → {@code ENERGY},
     * {@code https://example.com/lib/math.evo} → {@code MATH}.
     *
     * @param path A resolved file path or URL.
     * @return The derived module name in uppercase.
     */
    public static String deriveModuleName(String path) {
        String segment = path;
        int lastSlash = segment.lastIndexOf('/');
        if (lastSlash >= 0) {
            segment = segment.substring(lastSlash + 1);
        }
        int dot = segment.lastIndexOf('.');
        if (dot > 0) {
            segment = segment.substring(0, dot);
        }
        return segment.toUpperCase();
    }

    @Override
    public String toString() {
        return path;
    }
}
