// in: src/main/java/org/evochora/runtime/isa/Instruction.java

package org.evochora.runtime.isa;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.isa.instructions.*;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;

import java.lang.reflect.Constructor;
import java.util.*;
import java.util.function.BiFunction;

/**
 * Die abstrakte Basisklasse für alle Instruktionen der Evochora VM.
 * Diese Klasse ist nun frei von Legacy-Compiler-Abhängigkeiten und konzentriert
 * sich ausschließlich auf die Laufzeit-Logik.
 */
public abstract class Instruction {

    protected final Organism organism;
    protected final int fullOpcodeId;

    public enum OperandSource { REGISTER, IMMEDIATE, STACK, VECTOR, LABEL }
    public record Operand(Object value, int rawSourceId) {}

    // --- Laufzeit-Registries ---
    private static final Map<Integer, Class<? extends Instruction>> REGISTERED_INSTRUCTIONS_BY_ID = new HashMap<>();
    private static final Map<String, Integer> NAME_TO_ID = new HashMap<>();
    private static final Map<Integer, String> ID_TO_NAME = new HashMap<>();
    private static final Map<Integer, Integer> ID_TO_LENGTH = new HashMap<>();
    private static final Map<Integer, BiFunction<Organism, Environment, Instruction>> REGISTERED_PLANNERS_BY_ID = new HashMap<>();
    protected static final Map<Integer, List<OperandSource>> OPERAND_SOURCES = new HashMap<>();
    private static final Map<Integer, InstructionSignature> SIGNATURES_BY_ID = new HashMap<>();

    // --- Register-Konstanten ---
    public static final int PR_BASE = 1000;
    public static final int FPR_BASE = 2000;

    public Instruction(Organism organism, int fullOpcodeId) {
        this.organism = organism;
        this.fullOpcodeId = fullOpcodeId;
    }

    protected Object readOperand(int id) {
        return organism.readOperand(id);
    }

    protected boolean writeOperand(int id, Object value) {
        return organism.writeOperand(id, value);
    }

    public List<Operand> resolveOperands(Environment environment) {
        List<Operand> resolved = new ArrayList<>();
        List<OperandSource> sources = OPERAND_SOURCES.get(fullOpcodeId);
        if (sources == null) return resolved;

        int[] currentIp = organism.getIpBeforeFetch();

        for (OperandSource source : sources) {
            switch (source) {
                case STACK:
                    Object val = organism.getDataStack().pop();
                    resolved.add(new Operand(val, -1));
                    break;
                case REGISTER: {
                    Organism.FetchResult arg = organism.fetchArgument(currentIp, environment);
                    int regId = Molecule.fromInt(arg.value()).toScalarValue();
                    resolved.add(new Operand(readOperand(regId), regId));
                    currentIp = arg.nextIp();
                    break;
                }
                case IMMEDIATE: {
                    Organism.FetchResult arg = organism.fetchArgument(currentIp, environment);
                    resolved.add(new Operand(arg.value(), -1));
                    currentIp = arg.nextIp();
                    break;
                }
                case VECTOR: {
                    int[] vec = new int[Config.WORLD_DIMENSIONS];
                    for(int i=0; i<Config.WORLD_DIMENSIONS; i++) {
                        Organism.FetchResult res = organism.fetchSignedArgument(currentIp, environment);
                        vec[i] = res.value();
                        currentIp = res.nextIp();
                    }
                    resolved.add(new Operand(vec, -1));
                    break;
                }
                case LABEL: {
                    int[] delta = new int[Config.WORLD_DIMENSIONS];
                    for(int i=0; i<Config.WORLD_DIMENSIONS; i++) {
                        Organism.FetchResult res = organism.fetchSignedArgument(currentIp, environment);
                        delta[i] = res.value();
                        currentIp = res.nextIp();
                    }
                    resolved.add(new Operand(delta, -1));
                    break;
                }
            }
        }
        return resolved;
    }

    protected List<Object> peekFromDataStack(int count) {
        if (organism.getDataStack().size() < count) {
            return new java.util.ArrayList<>();
        }
        List<Object> peeked = new java.util.ArrayList<>(count);
        java.util.Iterator<Object> it = organism.getDataStack().iterator();
        int i = 0;
        while (it.hasNext() && i < count) {
            peeked.add(it.next());
            i++;
        }
        return peeked;
    }

    public abstract void execute(Simulation simulation);

    public int getCost(Organism organism, Environment environment, List<Integer> rawArguments) {
        return 1;
    }

    public static void init() {
        // Arithmetik-Familie
        registerFamily(ArithmeticInstruction.class, Map.of(4, "ADDR", 6, "SUBR", 40, "MULR", 42, "DIVR", 44, "MODR"), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(ArithmeticInstruction.class, Map.of(30, "ADDI", 31, "SUBI", 41, "MULI", 43, "DIVI", 45, "MODI"), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(ArithmeticInstruction.class, Map.of(70, "ADDS", 71, "SUBS", 72, "MULS", 73, "DIVS", 74, "MODS"), List.of(OperandSource.STACK, OperandSource.STACK));

        // Bitwise-Familie
        registerFamily(BitwiseInstruction.class, Map.of(5, "NADR", 46, "ANDR", 48, "ORR", 50, "XORR"), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(BitwiseInstruction.class, Map.of(32, "NADI", 47, "ANDI", 49, "ORI", 51, "XORI", 53, "SHLI", 54, "SHRI"), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(BitwiseInstruction.class, Map.of(78, "NADS", 75, "ANDS", 76, "ORS", 77, "XORS", 80, "SHLS", 81, "SHRS"), List.of(OperandSource.STACK, OperandSource.STACK));
        registerFamily(BitwiseInstruction.class, Map.of(52, "NOT"), List.of(OperandSource.REGISTER));
        registerFamily(BitwiseInstruction.class, Map.of(79, "NOTS"), List.of(OperandSource.STACK));

        // Data-Familie
        registerFamily(DataInstruction.class, Map.of(1, "SETI"), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(DataInstruction.class, Map.of(2, "SETR"), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(DataInstruction.class, Map.of(3, "SETV"), List.of(OperandSource.REGISTER, OperandSource.VECTOR));
        registerFamily(DataInstruction.class, Map.of(22, "PUSH"), List.of(OperandSource.REGISTER));
        registerFamily(DataInstruction.class, Map.of(23, "POP"), List.of(OperandSource.REGISTER));
        registerFamily(DataInstruction.class, Map.of(58, "PUSI"), List.of(OperandSource.IMMEDIATE));

        // Stack-Familie
        registerFamily(StackInstruction.class, Map.of(60, "DUP", 61, "SWAP", 62, "DROP", 63, "ROT"), List.of());

        // Conditional-Familie
        registerFamily(ConditionalInstruction.class, Map.of(7, "IFR", 8, "LTR", 9, "GTR", 33, "IFTR"), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(ConditionalInstruction.class, Map.of(24, "IFI", 25, "LTI", 26, "GTI", 29, "IFTI"), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(ConditionalInstruction.class, Map.of(85, "IFS", 86, "GTS", 87, "LTS", 88, "IFTS"), List.of(OperandSource.STACK, OperandSource.STACK));
        registerFamily(ConditionalInstruction.class, Map.of(93, "IFMR"), List.of(OperandSource.REGISTER));
        registerFamily(ConditionalInstruction.class, Map.of(94, "IFMI"), List.of(OperandSource.VECTOR));
        registerFamily(ConditionalInstruction.class, Map.of(95, "IFMS"), List.of(OperandSource.STACK));

        // ControlFlow-Familie
        registerFamily(ControlFlowInstruction.class, Map.of(20, "JMPI", 34, "CALL"), List.of(OperandSource.LABEL));
        registerFamily(ControlFlowInstruction.class, Map.of(10, "JMPR"), List.of(OperandSource.REGISTER));
        registerFamily(ControlFlowInstruction.class, Map.of(89, "JMPS"), List.of(OperandSource.STACK));
        registerFamily(ControlFlowInstruction.class, Map.of(35, "RET"), List.of());

        // WorldInteraction (POKE & PEEK)
        registerFamily(EnvironmentInteractionInstruction.class, Map.of(15, "POKE", 14, "PEEK"), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(EnvironmentInteractionInstruction.class, Map.of(57, "POKI", 56, "PEKI"), List.of(OperandSource.REGISTER, OperandSource.VECTOR));
        registerFamily(EnvironmentInteractionInstruction.class, Map.of(91, "POKS"), List.of(OperandSource.STACK, OperandSource.STACK));
        registerFamily(EnvironmentInteractionInstruction.class, Map.of(90, "PEKS"), List.of(OperandSource.STACK));

        // State (SCAN, SEEK & Rest)
        registerFamily(StateInstruction.class, Map.of(16, "SCAN"), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(StateInstruction.class, Map.of(82, "SCNI"), List.of(OperandSource.REGISTER, OperandSource.VECTOR));
        registerFamily(StateInstruction.class, Map.of(83, "SCNS"), List.of(OperandSource.STACK));
        registerFamily(StateInstruction.class, Map.of(12, "SEEK"), List.of(OperandSource.REGISTER));
        registerFamily(StateInstruction.class, Map.of(59, "SEKI"), List.of(OperandSource.VECTOR));
        registerFamily(StateInstruction.class, Map.of(84, "SEKS"), List.of(OperandSource.STACK));
        registerFamily(StateInstruction.class, Map.of(11, "TURN", 17, "NRG", 19, "DIFF", 21, "POS", 55, "RAND"), List.of(OperandSource.REGISTER));
        registerFamily(StateInstruction.class, Map.of(18, "FORK"), List.of(OperandSource.REGISTER, OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(StateInstruction.class, Map.of(13, "SYNC", 92, "NRGS"), List.of());

        // NOP
        registerFamily(NopInstruction.class, Map.of(0, "NOP"), List.of());
    }

    private static void registerFamily(Class<? extends Instruction> familyClass, Map<Integer, String> variants, List<OperandSource> sources) {
        try {
            Constructor<? extends Instruction> constructor = familyClass.getConstructor(Organism.class, int.class);
            List<InstructionArgumentType> argTypesForSignature = new ArrayList<>();
            int length = 1;
            for (OperandSource s : sources) {
                if (s == OperandSource.REGISTER) {
                    length++;
                    argTypesForSignature.add(InstructionArgumentType.REGISTER);
                } else if (s == OperandSource.IMMEDIATE) {
                    length++;
                    argTypesForSignature.add(InstructionArgumentType.LITERAL);
                } else if (s == OperandSource.VECTOR) {
                    length += Config.WORLD_DIMENSIONS;
                    argTypesForSignature.add(InstructionArgumentType.VECTOR);
                } else if (s == OperandSource.LABEL) {
                    length += Config.WORLD_DIMENSIONS;
                    argTypesForSignature.add(InstructionArgumentType.LABEL);
                }
            }
            InstructionSignature signature = new InstructionSignature(argTypesForSignature);

            for (Map.Entry<Integer, String> entry : variants.entrySet()) {
                int id = entry.getKey();
                String name = entry.getValue();

                BiFunction<Organism, Environment, Instruction> planner = (org, world) -> {
                    try {
                        return constructor.newInstance(org, world.getMolecule(org.getIp()).toInt());
                    } catch (Exception e) { throw new RuntimeException("Failed to plan instruction " + name, e); }
                };

                registerInstruction(familyClass, id, name, length, planner, signature);
                OPERAND_SOURCES.put(id | Config.TYPE_CODE, sources);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to register instruction family " + familyClass.getSimpleName(), e);
        }
    }

    private static void registerInstruction(Class<? extends Instruction> instructionClass, int id, String name, int length,
                                            BiFunction<Organism, Environment, Instruction> planner, InstructionSignature signature) {
        String upperCaseName = name.toUpperCase();
        int fullId = id | Config.TYPE_CODE;
        REGISTERED_INSTRUCTIONS_BY_ID.put(fullId, instructionClass);
        NAME_TO_ID.put(upperCaseName, fullId);
        ID_TO_NAME.put(fullId, name);
        ID_TO_LENGTH.put(fullId, length);
        REGISTERED_PLANNERS_BY_ID.put(fullId, planner);
        SIGNATURES_BY_ID.put(fullId, signature);
    }

    public static Optional<Integer> resolveRegToken(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String u = token.toUpperCase();
        try {
            if (u.startsWith("%DR")) {
                return Optional.of(Integer.parseInt(u.substring(3)));
            }
            if (u.startsWith("%PR")) {
                return Optional.of(PR_BASE + Integer.parseInt(u.substring(3)));
            }
            if (u.startsWith("%FPR")) {
                return Optional.of(FPR_BASE + Integer.parseInt(u.substring(4)));
            }
        } catch (NumberFormatException ignore) {
            // Fällt durch zum leeren Optional, wenn z.B. "%DR" ohne Zahl kommt.
        }
        return Optional.empty();
    }

    // --- Statische Getter für Laufzeit-Informationen ---

    public int getLength() { return getInstructionLengthById(this.fullOpcodeId); }
    public final Organism getOrganism() { return this.organism; }
    public final String getName() { return ID_TO_NAME.getOrDefault(this.fullOpcodeId, "UNKNOWN"); }
    public static String getInstructionNameById(int id) { return ID_TO_NAME.getOrDefault(id, "UNKNOWN"); }
    public static int getInstructionLengthById(int id) { return ID_TO_LENGTH.getOrDefault(id, 1); }
    public static Integer getInstructionIdByName(String name) { return NAME_TO_ID.get(name.toUpperCase()); }
    public static BiFunction<Organism, Environment, Instruction> getPlannerById(int id) { return REGISTERED_PLANNERS_BY_ID.get(id); }
    public static Optional<InstructionSignature> getSignatureById(int id) { return Optional.ofNullable(SIGNATURES_BY_ID.get(id)); }
    public static Map<Integer, String> getIdToNameMap() {
        java.util.Map<Integer, String> cleanMap = new java.util.HashMap<>();
        for (java.util.Map.Entry<Integer, String> entry : ID_TO_NAME.entrySet()) {
            cleanMap.put(entry.getKey() & Config.VALUE_MASK, entry.getValue());
        }
        return java.util.Collections.unmodifiableMap(cleanMap);
    }

    // --- Konfliktlösungs-Logik ---

    protected boolean executedInTick = false;
    public enum ConflictResolutionStatus { NOT_APPLICABLE, WON_EXECUTION, LOST_TARGET_OCCUPIED, LOST_TARGET_EMPTY, LOST_LOWER_ID_WON, LOST_OTHER_REASON }
    protected ConflictResolutionStatus conflictStatus = ConflictResolutionStatus.NOT_APPLICABLE;

    public boolean isExecutedInTick() { return executedInTick; }
    public void setExecutedInTick(boolean executedInTick) { this.executedInTick = executedInTick; }
    public ConflictResolutionStatus getConflictStatus() { return conflictStatus; }
    public void setConflictStatus(ConflictResolutionStatus conflictStatus) { this.conflictStatus = conflictStatus; }
}