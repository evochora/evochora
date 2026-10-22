package org.evochora.compiler.internal;

import org.evochora.compiler.api.PlacedMolecule;

/**
 * One molecule a program places in the JSON form of an artifact: its position relative to the
 * program's origin and the molecule, named as the {@code PlacedMoleculeMapping} of
 * {@code metadata_contracts.proto} names them.
 *
 * @param position The coordinate relative to the program's origin.
 * @param molecule The molecule placed there.
 */
public record PlacedMoleculeMapping(int[] position, PlacedMolecule molecule) {
}
