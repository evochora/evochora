package org.evochora.compiler.api;

/**
 * Repräsentiert ein Symbol, das an einer bestimmten Koordinate in der Welt platziert werden soll.
 * Diese Klasse ist Teil der öffentlichen API und entkoppelt die API von der internen
 * {@code org.evochora.runtime.model.Symbol}-Implementierung.
 *
 * @param type Der Typ des Symbols (z.B. CODE, DATA, ENERGY).
 * @param value Der Wert des Symbols.
 */
public record PlacedSymbol(int type, int value) {}
