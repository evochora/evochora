package org.evochora.compiler.api;

/**
 * Represents a molecule to be placed at a specific coordinate in the world.
 * This class is part of the public API and decouples the API from the internal
 * {@code org.evochora.runtime.model.Molecule} implementation.
 *
 * @param type The type of the symbol (e.g., CODE, DATA, ENERGY).
 * @param value The value of the symbol.
 */
public record PlacedMolecule(int type, int value) {}
