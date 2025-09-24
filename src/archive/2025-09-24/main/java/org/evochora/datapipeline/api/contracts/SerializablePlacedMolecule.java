package org.evochora.datapipeline.api.contracts;

/**
 * A serializable representation of a molecule to be placed in the world.
 *
 * @param type  The type of the molecule.
 * @param value The value of the molecule.
 */
public record SerializablePlacedMolecule(
    int type,
    int value
) {
}
