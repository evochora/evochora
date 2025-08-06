// src/main/java/org/evochora/assembler/AssemblerOutput.java
package org.evochora.assembler;

import java.util.List;

/**
 * Ein versiegelter Record, der alle möglichen Arten von Assembler-Ausgaben repräsentiert.
 * Dies dient als typsicherer Rückgabetyp für die Action.assemble()-Methoden.
 */
public sealed interface AssemblerOutput {

    /**
     * Repräsentiert eine Sequenz von Maschinenbefehlen (Opcodes oder normale Argumente).
     * Dies ist der Standardfall für die meisten Anweisungen.
     */
    public record CodeSequence(List<Integer> machineCode) implements AssemblerOutput {}

    /**
     * Repräsentiert einen Sprungbefehl, dessen Ziel erst im zweiten Assembler-Pass
     * aufgelöst werden kann. Enthält den Namen des Labels.
     */
    public record JumpInstructionRequest(String labelName) implements AssemblerOutput {}

    /**
     * Repräsentiert einen Sprungbefehl mit einer Delta-Koordinate.
     * Dies wird vom Assembler nach der Auflösung eines JumpInstructionRequest erstellt.
     * Es enthält den Opcode selbst und die berechneten Delta-Argumente.
     */
    public record JumpCode(List<Integer> machineCode) implements AssemblerOutput {}

    /**
     * Repräsentiert eine Anforderung, einen Vektor mit einem Labelnamen aufzulösen.
     * Dies wird von Assembler-Anweisungen wie .PLACE verwendet (z.B. .PLACE DATA MY_LABEL 1 2).
     * Enthält den Namen des Labels.
     */
    public record VectorInstructionRequest(String labelName) implements AssemblerOutput {}

    /**
     * NEU: Repräsentiert eine Anfrage, ein Label in eine Koordinate umzuwandeln und als
     * Vektor im Maschinencode zu speichern. Wird von Anweisungen wie SETV verwendet.
     */
    public record LabelToVectorRequest(String labelName, int registerId) implements AssemblerOutput {}
}