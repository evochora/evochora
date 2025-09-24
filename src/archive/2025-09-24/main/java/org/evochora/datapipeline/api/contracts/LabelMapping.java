package org.evochora.datapipeline.api.contracts;

/**
 * Maps a linear address in the compiled program to a human-readable label.
 *
 * @param linearAddress The linear address in the machine code layout.
 * @param labelName     The name of the label at that address.
 */
public record LabelMapping(
    int linearAddress,
    String labelName
) {
}
