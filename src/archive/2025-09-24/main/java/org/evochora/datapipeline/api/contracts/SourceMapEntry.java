package org.evochora.datapipeline.api.contracts;

/**
 * Maps a linear address in the compiled program to its original source code location.
 *
 * @param linearAddress The linear address in the machine code layout.
 * @param sourceInfo    The corresponding source code location information.
 */
public record SourceMapEntry(
    int linearAddress,
    SerializableSourceInfo sourceInfo
) {
}
