// src/main/java/org/evochora/AssemblyProgram.java
package org.evochora;

import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public abstract class AssemblyProgram {
    // Statische Dictionaries, die für alle Programme gelten
    protected static final Map<String, Map<String, Object>> programIdToMetadata = new HashMap<>();
    protected static final Map<Integer, String> organismIdToProgramId = new HashMap<>();

    // Instanzvariablen für das Ergebnis der Assemblierung
    protected Map<int[], Integer> machineCodeLayout;
    protected String programId;
    protected Map<int[], Symbol> initialWorldObjects;

    /**
     * Muss von jeder Unterklasse implementiert werden.
     * @return Der menschenlesbare Assembly-Code als String.
     */
    public abstract String getAssemblyCode();

    /**
     * Übersetzt den Assembly-Code in ein räumliches Layout von Maschinencode.
     * Diese Version delegiert die Logik an die jeweiligen Action-Klassen.
     * @return Eine Map von relativen Koordinaten zu den Maschinencode-Werten.
     */
    public Map<int[], Integer> assemble() {
        if (this.machineCodeLayout != null) {
            return this.machineCodeLayout;
        }

        Map<String, Integer> registerMap = new HashMap<>();
        Map<String, Integer> labelMap = new HashMap<>();
        this.initialWorldObjects = new HashMap<>();
        this.machineCodeLayout = new LinkedHashMap<>();

        String[] lines = getAssemblyCode().split("\\r?\\n");

        // --- PHASE 1: Labels, .REG und .PLACE sammeln ---
        int linearAddress = 0;
        int[] currentPos = new int[Config.WORLD_DIMENSIONS];
        int[] currentDv = new int[Config.WORLD_DIMENSIONS];
        currentDv[0] = 1;

        for (String line : lines) {
            line = line.split("#")[0].strip();
            if (line.isEmpty()) continue;

            String[] parts = line.split("\\s+");
            String directive = parts[0].toUpperCase();

            if (line.endsWith(":")) {
                String label = line.substring(0, line.length() - 1).toUpperCase();
                labelMap.put(label, linearAddress);
            } else if (directive.equals(".REG")) {
                registerMap.put(parts[1].toUpperCase(), Integer.parseInt(parts[2]));
            } else if (directive.equals(".PLACE")) {
                // .PLACE Logik...
            } else if (directive.equals(".DIR")) {
                // .DIR Logik...
            } else {
                // Es ist ein Befehl, erhöhe die Adresse um seine Länge
                Integer opcode = Config.NAME_TO_OPCODE.get(directive);
                if (opcode == null) throw new IllegalArgumentException("Unbekannter Befehl in Phase 1: " + directive);
                Config.Opcode opcodeDef = Config.OPCODE_DEFINITIONS.get(opcode);
                linearAddress += opcodeDef.length();
            }
        }

        // --- PHASE 2: Maschinencode und Layout generieren ---
        for (String line : lines) {
            line = line.split("#")[0].strip();
            if (line.isEmpty() || line.startsWith(".") || line.endsWith(":")) {
                if (line.toUpperCase().startsWith(".DIR")) {
                    String[] parts = line.split("\\s+");
                    String[] vectorComponents = parts[1].split("\\|");
                    for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
                        currentDv[i] = Integer.parseInt(vectorComponents[i].strip());
                    }
                }
                continue;
            }

            String[] parts = line.split("\\s+", 2);
            String opcodeName = parts[0].toUpperCase();
            String argsStr = (parts.length > 1) ? parts[1] : "";
            String[] args = argsStr.isEmpty() ? new String[0] : argsStr.split("\\s+");

            Integer opcode = Config.NAME_TO_OPCODE.get(opcodeName);
            Config.AssemblerPlanner assembler = Config.OPCODE_TO_ASSEMBLER.get(opcode);

            if (assembler == null) throw new IllegalArgumentException("Kein Assembler für Befehl gefunden: " + opcodeName);

            // Opcode platzieren
            this.machineCodeLayout.put(Arrays.copyOf(currentPos, currentPos.length), opcode);
            for(int i=0; i<currentPos.length; i++) currentPos[i] += currentDv[i];

            // Argumente von der Action-Klasse assemblieren lassen und platzieren
            List<Integer> assembledArgs = assembler.apply(args, registerMap, labelMap);
            for (int argValue : assembledArgs) {
                this.machineCodeLayout.put(Arrays.copyOf(currentPos, currentPos.length), argValue);
                for(int i=0; i<currentPos.length; i++) currentPos[i] += currentDv[i];
            }
        }

        // Erzeuge die programId als Hash des Maschinencodes
        List<Integer> machineCodeForHash = new ArrayList<>(this.machineCodeLayout.values());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer buffer = ByteBuffer.allocate(machineCodeForHash.size() * 4);
            for (int code : machineCodeForHash) buffer.putInt(code);
            byte[] hashBytes = digest.digest(buffer.array());
            this.programId = Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(hashBytes, 12));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 nicht gefunden", e);
        }

        // Metadaten speichern
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("registerMap", registerMap);
        metadata.put("labelMap", labelMap);
        programIdToMetadata.put(this.programId, metadata);

        return this.machineCodeLayout;
    }

    public Map<int[], Symbol> getInitialWorldObjects() {
        return this.initialWorldObjects;
    }

    public void assignOrganism(Organism organism) {
        if (this.programId == null) {
            throw new IllegalStateException("Muss zuerst .assemble() aufrufen.");
        }
        organismIdToProgramId.put(organism.getId(), this.programId);
    }

    public static String disassembleLine(int organismId) {
        // TODO: Implementierung folgt
        return null;
    }
}