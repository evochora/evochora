package org.evochora.compiler.api;

/**
 * Repräsentiert ein Molecule, das an einer bestimmten Koordinate in der Welt platziert werden soll.
 * Diese Klasse ist Teil der öffentlichen API und entkoppelt die API von der internen
 * {@code org.evochora.runtime.model.Molecule}-Implementierung.
 *
 * @param type Der Typ des Symbols (z.B. CODE, DATA, ENERGY).
 * @param value Der Wert des Symbols.
 */
public record PlacedMolecule(int type, int value) {}
