// src/main/java/org/evochora/AssemblyProgram.java
package org.evochora;

import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public abstract class AssemblyProgram {
    protected static final Map<String, Object> programIdToMetadata = new HashMap<>();
    protected static final Map<Integer, String> organismIdToProgramId = new HashMap<>();

    protected List<Integer> machineCode;
    protected String programId;
    protected Map<int[], Integer> layout;
    protected Map<int[], Symbol> initialWorldObjects = new HashMap<>();

    public abstract String getAssemblyCode();

    public Map<int[], Integer> assemble() {
        if (this.layout != null && !this.layout.isEmpty()) return this.layout;

        this.layout = new LinkedHashMap<>();
        Map<String, Integer> registerMap = new HashMap<>();
        this.initialWorldObjects.clear();
        String[] lines = getAssemblyCode().split("\\r?\\n");

        int[] currentPos = new int[Config.WORLD_DIMENSIONS];
        int[] currentDv = new int[Config.WORLD_DIMENSIONS];
        currentDv[0] = 1;

        for (String line : lines) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;

            String[] parts = line.split("\\s+");
            String directive = parts[0].toUpperCase();

            if (directive.equals(".REG")) {
                registerMap.put(parts[1].toUpperCase(), Integer.parseInt(parts[2]));
            } else if (directive.equals(".PLACE")) {
                if (parts.length != 3 + Config.WORLD_DIMENSIONS) throw new IllegalArgumentException(".PLACE erwartet " + Config.WORLD_DIMENSIONS + " Koordinaten.");
                String typeName = parts[1].toUpperCase();
                int value = Integer.parseInt(parts[2]);
                int[] relativePos = new int[Config.WORLD_DIMENSIONS];
                for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
                    relativePos[i] = Integer.parseInt(parts[3 + i]);
                }
                int type = switch (typeName) {
                    case "ENERGY" -> Config.TYPE_ENERGY;
                    case "STRUCTURE" -> Config.TYPE_STRUCTURE;
                    case "DATA" -> Config.TYPE_DATA;
                    case "CODE" -> Config.TYPE_CODE;
                    default -> throw new IllegalArgumentException("Unbekannter Typ in .PLACE: " + typeName);
                };
                initialWorldObjects.put(relativePos, new Symbol(type, value));
            }
        }

        for (String line : lines) {
            line = line.split("#")[0].strip();
            if (line.isEmpty() || line.startsWith(".")) {
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

            String[] parts = line.split("\\s+", 2);
            String opcodeName = parts[0].toUpperCase();
            String argsStr = (parts.length > 1) ? parts[1] : "";

            Integer opcode = Config.NAME_TO_OPCODE.get(opcodeName);
            if (opcode == null) throw new IllegalArgumentException("Unbekannter Befehl: " + opcodeName);

            this.layout.put(Arrays.copyOf(currentPos, currentPos.length), opcode);
            for(int i=0; i<currentPos.length; i++) currentPos[i] += currentDv[i];

            if (!argsStr.isEmpty()) {
                String[] args = argsStr.split("\\s+");
                if (opcodeName.equals("SETV")) {
                    String regName = args[0].toUpperCase();
                    if (!registerMap.containsKey(regName)) throw new IllegalArgumentException("Unbekanntes Register in SETV: " + regName);
                    this.layout.put(Arrays.copyOf(currentPos, currentPos.length), registerMap.get(regName));
                    for(int i=0; i<currentPos.length; i++) currentPos[i] += currentDv[i];

                    String[] vectorComponents = args[1].split("\\|");
                    if (vectorComponents.length != Config.WORLD_DIMENSIONS) throw new IllegalArgumentException("Falsche Vektor-Dimensionalität bei SETV");
                    for (String component : vectorComponents) {
                        this.layout.put(Arrays.copyOf(currentPos, currentPos.length), Integer.parseInt(component.strip()));
                        for(int i=0; i<currentPos.length; i++) currentPos[i] += currentDv[i];
                    }
                } else {
                    for (String arg : args) {
                        arg = arg.strip().toUpperCase();
                        int value;
                        if (arg.startsWith("%")) {
                            if (!registerMap.containsKey(arg)) throw new IllegalArgumentException("Unbekanntes Register: " + arg);
                            value = registerMap.get(arg);
                        } else {
                            value = Integer.parseInt(arg);
                        }
                        this.layout.put(Arrays.copyOf(currentPos, currentPos.length), value);
                        for(int i=0; i<currentPos.length; i++) currentPos[i] += currentDv[i];
                    }
                }
            }
        }

        this.machineCode = new ArrayList<>(this.layout.values());

        try {
            // KORRIGIERTER ALGORITHMUS-NAME
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer buffer = ByteBuffer.allocate(machineCode.size() * 4);
            for (int code : machineCode) buffer.putInt(code);
            byte[] hashBytes = digest.digest(buffer.array());
            this.programId = Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(hashBytes, 12));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 nicht gefunden", e);
        }

        programIdToMetadata.put(this.programId, Map.of("registerMap", registerMap));
        return this.layout;
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