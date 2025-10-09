/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

package org.evochora.datapipeline.utils;

/**
 * Utility class for expanding environment variables and system properties in path strings.
 * <p>
 * This utility is used by both storage and database resources to support configurable paths
 * with variable substitution, enabling flexible deployment across different environments.
 * <p>
 * <strong>Syntax:</strong> {@code ${VAR}} for both environment variables and system properties.
 * System properties are checked first, then environment variables.
 * <p>
 * <strong>Examples:</strong>
 * <ul>
 *   <li>{@code ${user.home}/data} → {@code /home/user/data} (Java system property)</li>
 *   <li>{@code ${HOME}/evochora} → {@code /home/user/evochora} (environment variable)</li>
 *   <li>{@code ${EVOCHORA_DATA_DIR}} → {@code /var/lib/evochora} (custom env variable)</li>
 *   <li>{@code ${user.home}/${PROJECT}/data} → multiple variable expansion</li>
 * </ul>
 *
 * @see org.evochora.datapipeline.resources.storage.FileSystemStorageResource
 * @see org.evochora.datapipeline.resources.database.H2Database
 */
public final class PathExpansion {

    private PathExpansion() {
        // Utility class - prevent instantiation
    }

    /**
     * Expands environment variables and Java system properties in a path string.
     * <p>
     * Supports syntax: {@code ${VAR}} for both environment variables and system properties.
     * System properties are checked first, then environment variables.
     * <p>
     * Variable resolution order:
     * <ol>
     *   <li>Java system properties (e.g., {@code user.home}, {@code java.io.tmpdir})</li>
     *   <li>Environment variables (e.g., {@code HOME}, {@code USERPROFILE}, {@code EVOCHORA_DATA_DIR})</li>
     * </ol>
     * <p>
     * <strong>Examples:</strong>
     * <pre>
     * expandPath("${user.home}/data")              → "/home/user/data"
     * expandPath("${HOME}/evochora")               → "/home/user/evochora"
     * expandPath("${user.home}/${PROJECT}/data")   → "/home/user/myproject/data"
     * expandPath("/absolute/path/no/variables")    → "/absolute/path/no/variables"
     * </pre>
     *
     * @param path the path potentially containing variables like {@code ${HOME}} or {@code ${user.home}}
     * @return the path with all variables expanded
     * @throws IllegalArgumentException if a variable is referenced but not defined, or if a variable syntax is malformed
     */
    public static String expandPath(String path) {
        if (path == null || !path.contains("${")) {
            return path;
        }

        StringBuilder result = new StringBuilder();
        int pos = 0;

        while (pos < path.length()) {
            int startVar = path.indexOf("${", pos);
            if (startVar == -1) {
                // No more variables, append rest of string
                result.append(path.substring(pos));
                break;
            }

            // Append text before variable
            result.append(path.substring(pos, startVar));

            int endVar = path.indexOf("}", startVar + 2);
            if (endVar == -1) {
                throw new IllegalArgumentException("Unclosed variable in path: " + path);
            }

            String varName = path.substring(startVar + 2, endVar);
            String value = resolveVariable(varName);

            if (value == null) {
                throw new IllegalArgumentException(
                    "Undefined variable '${" + varName + "}' in path: " + path +
                    ". Check that environment variable or system property exists."
                );
            }

            result.append(value);
            pos = endVar + 1;
        }

        return result.toString();
    }

    /**
     * Resolves a variable name to its value, checking system properties first, then environment variables.
     * <p>
     * This precedence ensures that system properties (which can be set via {@code -D} flags)
     * can override environment variables, providing flexibility in different deployment scenarios.
     *
     * @param varName the variable name (without {@code ${}} delimiters)
     * @return the resolved value, or {@code null} if not found
     */
    private static String resolveVariable(String varName) {
        // Check system properties first (e.g., user.home, java.io.tmpdir)
        String value = System.getProperty(varName);
        if (value != null) {
            return value;
        }

        // Check environment variables (e.g., HOME, USERPROFILE, EVOCHORA_DATA_DIR)
        return System.getenv(varName);
    }
}

