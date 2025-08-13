package org.evochora.runtime.isa;

import java.util.List;

/**
 * Eine "Markierungs-Schnittstelle" für Instruktionen, die direkt Zellen in der Welt verändern
 * und daher an der Konfliktlösung beteiligt sein müssen.
 * Sie enthält keine eigenen Methoden mehr, da alle benötigten Methoden
 * von der Instruction-Basisklasse geerbt werden.
 */
public interface IEnvironmentModifyingInstruction {

    /**
     * Gibt eine Liste der n-dimensionalen Koordinaten zurück, die diese Instruktion
     * zu verändern versucht. Dies ist entscheidend für die Konfliktlösung.
     * @return Eine Liste von int[] Arrays, wobei jedes Array eine Koordinate darstellt.
     */
    List<int[]> getTargetCoordinates();

}