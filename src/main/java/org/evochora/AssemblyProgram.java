// src/main/java/org/evochora/AssemblyProgram.java
package org.evochora;

import org.evochora.organism.Organism;
import org.evochora.world.World;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class AssemblyProgram {
    // Statische Dictionaries, die für alle Programme gelten
    protected static final Map<String, Object> programIdToMetadata = new HashMap<>();
    protected static final Map<Integer, String> organismIdToProgramId = new HashMap<>();

    // Instanzvariablen für das Ergebnis der Assemblierung
    protected List<Integer> machineCode;
    protected String programId;

    /**
     * Muss von jeder Unterklasse implementiert werden.
     * @return Der menschenlesbare Assembly-Code als String.
     */
    public abstract String getAssemblyCode();

    /**
     * Übersetzt den Assembly-Code in Maschinencode.
     * Diese Version verarbeitet .REG Direktiven und einfache Befehle ohne Labels.
     * @return Eine Liste von Integern, die den Maschinencode repräsentiert.
     */
    public List<Integer> assemble() {
        if (this.machineCode != null) {
            return this.machineCode; // Nur einmal assemblieren
        }

        List<Integer> machineCodeList = new ArrayList<>();
        Map<String, Integer> registerMap = new HashMap<>();

        String[] lines = getAssemblyCode().split("\\r?\\n");

        // Phase 1: Register-Definitionen sammeln
        for (String line : lines) {
            line = line.strip();
            if (line.toUpperCase().startsWith(".REG")) {
                String[] parts = line.split("\\s+");
                if (parts.length != 3 || !parts[1].startsWith("%")) {
                    throw new IllegalArgumentException("Syntaxfehler in .REG-Direktive: " + line);
                }
                String regName = parts[1].toUpperCase();
                int regId = Integer.parseInt(parts[2]);
                registerMap.put(regName, regId);
            }
        }

        // Phase 2: Maschinencode generieren
        for (String line : lines) {
            line = line.split("#")[0].strip();
            if (line.isEmpty() || line.toUpperCase().startsWith(".REG")) {
                continue;
            }

            String[] parts = line.split("\\s+", 2);
            String opcodeName = parts[0].toUpperCase();
            String argsStr = (parts.length > 1) ? parts[1] : "";

            Integer opcode = Config.NAME_TO_OPCODE.get(opcodeName);
            if (opcode == null) {
                throw new IllegalArgumentException("Unbekannter Befehl: " + opcodeName);
            }
            machineCodeList.add(opcode);

            if (!argsStr.isEmpty()) {
                String[] args = argsStr.split("\\s+");

                // --- NEUE LOGIK FÜR SETV ---
                if (opcodeName.equals("SETV")) {
                    if (args.length != 2 || !args[0].startsWith("%")) {
                        throw new IllegalArgumentException("Syntaxfehler bei SETV: Erwartet 'SETV %REG X|Y|...'");
                    }
                    // Verarbeite das Register-Argument
                    String regName = args[0].toUpperCase();
                    if (!registerMap.containsKey(regName)) {
                        throw new IllegalArgumentException("Unbekanntes Register in SETV: " + regName);
                    }
                    machineCodeList.add(registerMap.get(regName));

                    // Verarbeite das Vektor-Literal
                    String[] vectorComponents = args[1].split("\\|");
                    for (String component : vectorComponents) {
                        machineCodeList.add(Integer.parseInt(component.strip()));
                    }
                } else {
                    // --- Bestehende Logik für alle anderen Befehle ---
                    for (String arg : args) {
                        arg = arg.strip().toUpperCase();
                        if (arg.startsWith("%")) { // Register
                            if (!registerMap.containsKey(arg)) {
                                throw new IllegalArgumentException("Unbekanntes Register: " + arg);
                            }
                            machineCodeList.add(registerMap.get(arg));
                        } else { // Literal
                            machineCodeList.add(Integer.parseInt(arg));
                        }
                    }
                }
            }
        }

        this.machineCode = machineCodeList;

        // Erzeuge die programId als Hash des Maschinencodes
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer buffer = ByteBuffer.allocate(machineCode.size() * 4);
            for (int code : machineCode) {
                buffer.putInt(code);
            }
            byte[] hashBytes = digest.digest(buffer.array());
            this.programId = Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(hashBytes, 12));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 nicht gefunden", e);
        }

        // Metadaten speichern
        programIdToMetadata.put(this.programId, Map.of("registerMap", registerMap));

        return this.machineCode;
    }

    /**
     * Verknüpft eine laufende Organismus-ID mit der Programm-ID dieser Instanz.
     * @param organismId Die von der Simulation vergebene ID.
     */
    public void assignOrganism(int organismId) {
        if (this.programId == null) {
            throw new IllegalStateException("Muss zuerst .assemble() aufrufen.");
        }
        organismIdToProgramId.put(organismId, this.programId);
    }

    /**
     * Die Schnittstelle für den intelligenten Logger. (Platzhalter)
     */
    public static String disassembleLine(int organismId) {
        // TODO: Hier kommt die Disassembler-Logik hin.
        return null;
    }
}