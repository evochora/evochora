package org.evochora.node.processes.http.api.visualizer.dto;

import java.util.List;

/**
 * Response DTO for the mutations of one organism's whole lineage.
 * <p>
 * A mutation is recorded at the birth of the organism that received it, so an organism's own
 * record shows only what its own birth changed. What shaped the displayed body is the union of
 * the records along its ancestry, which is what this response carries: every event of the
 * displayed organism and of each ancestor, placed on the displayed body.
 * <p>
 * Cells are absolute world coordinates, already converted from the offsets the events are stored
 * with, and their molecules are split into type and value the way the environment endpoint reports
 * a cell, so a consumer compares fields rather than a bit layout.
 *
 * @param organismId The displayed organism, whose body the coordinates lie on
 * @param events The events of the whole lineage, oldest generation first
 */
public record OrganismMutationsResponseDto(
    int organismId,
    List<MutationEventView> events
) {

    /**
     * One mutation as it was recorded at one birth of the lineage.
     * <p>
     * The origin fields name that birth: the same event appears once, however many descendants
     * carry its cells. An event without cells changed no molecule — the label mask is one — and is
     * reported with an empty cell list rather than left out, so a consumer sees that it happened.
     *
     * @param originOrganismId The organism that received the mutation at its birth
     * @param originGeneration Its generation, zero for an organism placed at the start of the run
     * @param originGenomeHash Its genome hash at birth, as text because a 64-bit value loses
     *                         precision as a JSON number
     * @param originBirthTick The tick that birth happened at
     * @param eventIndex Ordinal of the event within that birth, in the order the plugins ran
     * @param pluginClass Fully qualified class name of the plugin that reported the event
     * @param kind Short name the plugin chose for what it did, for example {@code substitution}
     * @param dv The newborn's direction vector while the plugin ran
     * @param params Numbers whose meaning the reporting plugin documents
     * @param cells The cells the event wrote, on the displayed body; empty when it wrote none
     */
    public record MutationEventView(
        int originOrganismId,
        int originGeneration,
        String originGenomeHash,
        long originBirthTick,
        int eventIndex,
        String pluginClass,
        String kind,
        int[] dv,
        long[] params,
        List<MutationCellView> cells
    ) {}

    /**
     * One cell an event wrote, with the molecule that stood there and the one that replaced it.
     *
     * @param coordinates Absolute coordinates on the displayed body
     * @param before The molecule at that cell before the write
     * @param after The molecule at that cell after the write
     */
    public record MutationCellView(
        int[] coordinates,
        MoleculeView before,
        MoleculeView after
    ) {}

    /**
     * A molecule in the form the environment endpoint reports a cell in.
     *
     * @param moleculeType Molecule type as defined in {@code Config} (the type bits at their
     *                     position in the packed molecule, e.g. {@code Config.TYPE_LABEL}); the
     *                     metadata endpoint maps these values to names
     * @param moleculeValue Signed value of the molecule; for a CODE molecule this is the opcode
     */
    public record MoleculeView(
        int moleculeType,
        int moleculeValue
    ) {}
}
