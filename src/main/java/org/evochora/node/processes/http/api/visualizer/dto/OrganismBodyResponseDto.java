package org.evochora.node.processes.http.api.visualizer.dto;

import java.util.List;

/**
 * Response DTO for the organism body endpoint.
 * <p>
 * The body of an organism is the set of world cells it owns. Reporting it directly removes the
 * need to guess a bounding box around the organism and to separate its molecules from those of
 * its neighbours afterwards.
 * <p>
 * Cell coordinates are relative to {@link #initialPosition}, which makes the bodies of different
 * organisms directly comparable. {@code initialPosition} and {@code worldShape} travel with the
 * response so a reader can convert back: {@code absolute = (initial + relative + size) % size}
 * on a toroidal world, plain addition otherwise.
 *
 * @param tickNumber The tick the body was read at
 * @param organismId Identifier of the organism owning these cells
 * @param initialPosition Absolute coordinates the organism started at, the origin of the relative
 *                        coordinates
 * @param worldShape Size of the world per dimension
 * @param isToroidal Whether the world wraps around at its edges
 * @param cellCount Number of cells in this response
 * @param cells The owned cells
 */
public record OrganismBodyResponseDto(
    long tickNumber,
    int organismId,
    int[] initialPosition,
    int[] worldShape,
    boolean isToroidal,
    int cellCount,
    List<BodyCell> cells
) {

    /**
     * A single cell of an organism's body.
     * <p>
     * The owner is not repeated per cell: every cell of the response belongs to the organism the
     * request named. Neither is an opcode id, which would only repeat {@code moleculeValue} for
     * cells whose {@code moleculeType} already says they hold code.
     *
     * @param relative Coordinates relative to the organism's initial position
     * @param moleculeType Molecule type as defined in {@code Config} (the type bits at their
     *                     position in the packed molecule, e.g. {@code Config.TYPE_ENERGY});
     *                     the metadata endpoint maps these values to names
     * @param moleculeValue Signed value of the molecule; for a CODE molecule this is the opcode
     * @param marker Marker of the molecule (0-15). A marker other than 0 means the molecule is
     *               staged for handover to a child at the next reproduction; the handover clears
     *               it back to 0
     */
    public record BodyCell(
        int[] relative,
        int moleculeType,
        int moleculeValue,
        int marker
    ) {}
}
