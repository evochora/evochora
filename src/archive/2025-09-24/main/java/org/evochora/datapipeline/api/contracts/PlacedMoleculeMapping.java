package org.evochora.datapipeline.api.contracts;

/**
 * Represents a mapping between a position in the world and a molecule to be placed there.
 *
 * @param position The n-dimensional coordinate for the placement.
 * @param molecule The molecule to be placed at the specified position.
 */
public record PlacedMoleculeMapping(
    int[] position,
    SerializablePlacedMolecule molecule
) {
}
