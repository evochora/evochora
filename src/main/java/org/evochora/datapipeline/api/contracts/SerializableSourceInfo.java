package org.evochora.datapipeline.api.contracts;

/**
 * A serializable representation of a source code location.
 *
 * @param sourceName The name of the source file.
 * @param line       The line number in the source file.
 * @param column     The column number in the source file.
 */
public record SerializableSourceInfo(
    String sourceName,
    int line,
    int column
) {
}
