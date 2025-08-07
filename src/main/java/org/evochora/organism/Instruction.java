// src/main/java/org/evochora/organism/Instruction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.assembler.ArgumentType;
import org.evochora.Config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

public abstract class Instruction {
    protected final Organism organism;

    private static final Map<Integer, Class<? extends Instruction>> REGISTERED_INSTRUCTIONS_BY_ID = new HashMap<>();
    private static final Map<String, Integer> NAME_TO_ID = new HashMap<>();
    private static final Map<Integer, String> ID_TO_NAME = new HashMap<>();
    private static final Map<Integer, Integer> ID_TO_LENGTH = new HashMap<>();
    private static final Map<Integer, BiFunction<Organism, World, Instruction>> REGISTERED_PLANNERS_BY_ID = new HashMap<>();
    private static final Map<Integer, Instruction.AssemblerPlanner> REGISTERED_ASSEMBLERS_BY_ID = new HashMap<>();
    private static final Map<Integer, Map<Integer, ArgumentType>> ARGUMENT_TYPES_BY_ID = new HashMap<>();

    public static void init() {
        try {
            Class.forName(NopInstruction.class.getName());
            Class.forName(SetiInstruction.class.getName());
            Class.forName(SetrInstruction.class.getName());
            Class.forName(SetvInstruction.class.getName());
            Class.forName(AddrInstruction.class.getName());
            Class.forName(SubrInstruction.class.getName());
            Class.forName(NadrInstruction.class.getName());
            Class.forName(IfiInstruction.class.getName());
            Class.forName(JmprInstruction.class.getName());
            Class.forName(JmpiInstruction.class.getName());
            Class.forName(TurnInstruction.class.getName());
            Class.forName(SeekInstruction.class.getName());
            Class.forName(SyncInstruction.class.getName());
            Class.forName(PeekInstruction.class.getName());
            Class.forName(PokeInstruction.class.getName());
            Class.forName(ScanInstruction.class.getName());
            Class.forName(NrgInstruction.class.getName());
            Class.forName(ForkInstruction.class.getName());
            Class.forName(DiffInstruction.class.getName());
            Class.forName(PosInstruction.class.getName());
            Class.forName(PushInstruction.class.getName());
            Class.forName(PopInstruction.class.getName());
            Class.forName(IfrInstruction.class.getName());
            Class.forName(IftiInstruction.class.getName());
            Class.forName(AddiInstruction.class.getName());
            Class.forName(SubiInstruction.class.getName());
            Class.forName(NadiInstruction.class.getName());
            Class.forName(IftrInstruction.class.getName());
            Class.forName(CallInstruction.class.getName());
            Class.forName(RetInstruction.class.getName());

        } catch (ClassNotFoundException e) {
            System.err.println("Fehler beim Initialisieren des Befehlssatzes.");
            e.printStackTrace();
        }
    }

    protected boolean executedInTick = false;

    public enum ConflictResolutionStatus {
        NOT_APPLICABLE,
        WON_EXECUTION,
        LOST_TARGET_OCCUPIED,
        LOST_TARGET_EMPTY,
        LOST_LOWER_ID_WON,
        LOST_OTHER_REASON
    }

    protected ConflictResolutionStatus conflictStatus = ConflictResolutionStatus.NOT_APPLICABLE;

    public Instruction(Organism organism) {
        this.organism = organism;
    }

    public abstract String getName();
    public abstract int getLength();
    protected abstract int getFixedBaseCost();
    public abstract int getCost(Organism organism, World world, List<Integer> rawArguments);
    public abstract ArgumentType getArgumentType(int argIndex);

    public static ArgumentType getArgumentTypeFor(int opcodeFullId, int argIndex) {
        return ARGUMENT_TYPES_BY_ID.getOrDefault(opcodeFullId, Map.of()).getOrDefault(argIndex, ArgumentType.LITERAL);
    }

    @FunctionalInterface
    public interface AssemblerPlanner {
        AssemblerOutput apply(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap);
    }

    protected static void registerInstruction(Class<? extends Instruction> instructionClass, int id, String name, int length,
                                              BiFunction<Organism, World, Instruction> planner,
                                              Instruction.AssemblerPlanner assembler) {
        String upperCaseName = name.toUpperCase();
        if (REGISTERED_INSTRUCTIONS_BY_ID.containsKey(id)) {
            throw new IllegalArgumentException("Instruktions-ID " + id + " bereits registriert.");
        }
        if (NAME_TO_ID.containsKey(upperCaseName)) {
            throw new IllegalArgumentException("Instruktions-Name '" + name + "' bereits registriert.");
        }
        REGISTERED_INSTRUCTIONS_BY_ID.put(id, instructionClass);
        NAME_TO_ID.put(upperCaseName, id);
        ID_TO_NAME.put(id, name);
        ID_TO_LENGTH.put(id, length);
        REGISTERED_PLANNERS_BY_ID.put(id, planner);
        REGISTERED_ASSEMBLERS_BY_ID.put(id, assembler);

    }

    protected static void registerArgumentTypes(int instructionId, Map<Integer, ArgumentType> types) {
        ARGUMENT_TYPES_BY_ID.put(instructionId | Config.TYPE_CODE, types);
    }

    public static Class<? extends Instruction> getInstructionClassById(int id) {
        return REGISTERED_INSTRUCTIONS_BY_ID.get(id);
    }

    public static String getInstructionNameById(int id) {
        return ID_TO_NAME.getOrDefault(id, "UNKNOWN");
    }

    public static int getInstructionLengthById(int id) {
        return ID_TO_LENGTH.getOrDefault(id, 1);
    }

    public static Integer getInstructionIdByName(String name) {
        return NAME_TO_ID.get(name.toUpperCase());
    }

    public static BiFunction<Organism, World, Instruction> getPlannerById(int id) {
        return REGISTERED_PLANNERS_BY_ID.get(id);
    }

    public static Instruction.AssemblerPlanner getAssemblerById(int id) {
        return REGISTERED_ASSEMBLERS_BY_ID.get(id);
    }

    public final Organism getOrganism() {
        return this.organism;
    }

    public abstract void execute(Simulation simulation);

    public boolean isExecutedInTick() {
        return executedInTick;
    }

    public void setExecutedInTick(boolean executedInTick) {
        this.executedInTick = executedInTick;
    }

    public ConflictResolutionStatus getConflictStatus() {
        return conflictStatus;
    }

    public void setConflictStatus(ConflictResolutionStatus conflictStatus) {
        this.conflictStatus = conflictStatus;
    }
}