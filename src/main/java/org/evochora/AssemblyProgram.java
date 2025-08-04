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

    public abstract String getAssemblyCode();

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
                if (parts.length != 3 + Config.WORLD_DIMENSIONS) throw new IllegalArgumentException(".PLACE erwartet " + Config.WORLD_DIMENSIONS + " Koordinaten.");
                String typeName = parts[1].toUpperCase();
                int value = Integer.parseInt(parts[2]);
                int[] relativePos = new int[Config.WORLD_DIMENSIONS];
                for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
                    relativePos[i] = Integer.parseInt(parts[3 + i]);
                }
                // KORRIGIERT: Typ-Werte müssen nun die dynamische TYPE_SHIFT verwenden.
                // Außerdem muss der Wert maskiert werden, um sicherzustellen, dass er nicht
                // in den Typ-Bereich ragt (besonders wichtig für negative Literale).
                int type = switch (typeName) {
                    case "ENERGY" -> Config.TYPE_ENERGY;
                    case "STRUCTURE" -> Config.TYPE_STRUCTURE;
                    case "DATA" -> Config.TYPE_DATA;
                    case "CODE" -> Config.TYPE_CODE;
                    default -> throw new IllegalArgumentException("Unbekannter Typ in .PLACE: " + typeName);
                };
                initialWorldObjects.put(relativePos, new Symbol(type, value)); // Symbol-Konstruktor übernimmt Maskierung
            } else if (!directive.equals(".DIR")) {
                Integer opcode = Config.NAME_TO_OPCODE.get(directive);
                if (opcode == null) throw new IllegalArgumentException("Unbekannter Befehl in Phase 1: " + directive);
                Config.Opcode opcodeDef = Config.OPCODE_DEFINITIONS.get(opcode);
                linearAddress += opcodeDef.length();
            }
        }

        // --- PHASE 2: Maschinencode und Layout generieren ---
        int[] currentPos = new int[Config.WORLD_DIMENSIONS];
        int[] currentDv = new int[Config.WORLD_DIMENSIONS];
        currentDv[0] = 1;

        for (String line : lines) {
            line = line.split("#")[0].strip();
            // KORRIGIERTE LOGIK: Überspringe jetzt ALLE Direktiven und Labels in Phase 2
            if (line.isEmpty() || line.endsWith(":") || line.startsWith(".")) {
                if (line.toUpperCase().startsWith(".DIR")) {
                    String[] parts = line.split("\\s+");
                    String[] vectorComponents = parts[1].split("\\|");
                    if (vectorComponents.length != Config.WORLD_DIMENSIONS) throw new IllegalArgumentException(".DIR erwartet einen " + Config.WORLD_DIMENSIONS + "D Vektor.");
                    for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
                        currentDv[i] = Integer.parseInt(vectorComponents[i].strip());
                    }
                }
                continue;
            }

            // Ab hier wird nur noch Code verarbeitet, der ein Opcode sein muss
            String[] parts = line.split("\\s+", 2);
            String opcodeName = parts[0].toUpperCase();
            String argsStr = (parts.length > 1) ? parts[1] : "";
            String[] args = argsStr.isEmpty() ? new String[0] : argsStr.split("\\s+");

            Integer opcode = Config.NAME_TO_OPCODE.get(opcodeName);
            Config.AssemblerPlanner assembler = Config.OPCODE_TO_ASSEMBLER.get(opcode);

            if (assembler == null) throw new IllegalArgumentException("Kein Assembler für Befehl gefunden: " + opcodeName);

            machineCodeLayout.put(Arrays.copyOf(currentPos, currentPos.length), opcode);
            for(int i=0; i<currentPos.length; i++) currentPos[i] += currentDv[i];

            List<Integer> assembledArgs = assembler.apply(args, registerMap, labelMap);
            for (int argValue : assembledArgs) {
                // KORRIGIERT: Argumente sollten als TYPE_DATA platziert werden.
                // Der argValue wird hier explizit maskiert, um sicherzustellen,
                // dass nur die relevanten Bits des Werts in den 32-Bit-Integer eingehen,
                // bevor der TYPE_DATA-Header hinzugefügt wird.
                // Dies löst das Problem des "Überlaufens" negativer Werte in den Typ-Bereich.
                machineCodeLayout.put(Arrays.copyOf(currentPos, currentPos.length), Config.TYPE_DATA | (argValue & Config.VALUE_MASK));
                for(int i=0; i<currentPos.length; i++) currentPos[i] += currentDv[i];
            }
        }

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

    public static String disassembleLine(int organismId, World world) {
        // TODO: Implementierung folgt
        return null;
    }
}