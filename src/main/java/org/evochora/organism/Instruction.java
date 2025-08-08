package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.ArgumentType;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.organism.instructions.*;
import org.evochora.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

public abstract class Instruction {

    protected final Organism organism;
    protected final int fullOpcodeId;

    private static final Map<Integer, Class<? extends Instruction>> REGISTERED_INSTRUCTIONS_BY_ID = new HashMap<>();
    private static final Map<String, Integer> NAME_TO_ID = new HashMap<>();
    private static final Map<Integer, String> ID_TO_NAME = new HashMap<>();
    private static final Map<Integer, Integer> ID_TO_LENGTH = new HashMap<>();
    private static final Map<Integer, BiFunction<Organism, World, Instruction>> REGISTERED_PLANNERS_BY_ID = new HashMap<>();
    private static final Map<Integer, AssemblerPlanner> REGISTERED_ASSEMBLERS_BY_ID = new HashMap<>();
    private static final Map<Integer, Map<Integer, ArgumentType>> ARGUMENT_TYPES_BY_ID = new HashMap<>();

    public Instruction(Organism organism, int fullOpcodeId) {
        this.organism = organism;
        this.fullOpcodeId = fullOpcodeId;
    }

    public Instruction(Organism organism) {
        this.organism = organism;
        this.fullOpcodeId = -1;
    }

    public static void init() {
        // --- Daten & Speicher ---
        register(SetiInstruction.class, 1, "SETI");
        register(SetrInstruction.class, 2, "SETR");
        register(SetvInstruction.class, 3, "SETV");
        register(PushInstruction.class, 22, "PUSH");
        register(PopInstruction.class, 23, "POP");
        register(PusiInstruction.class, 58, "PUSI");

        // --- Arithmetik & Logik ---
        register(AddrInstruction.class, 4, "ADDR");
        register(SubrInstruction.class, 6, "SUBR");
        register(AddiInstruction.class, 30, "ADDI");
        register(SubiInstruction.class, 31, "SUBI");
        register(MulrInstruction.class, 40, "MULR");
        register(MuliInstruction.class, 41, "MULI");
        register(DivrInstruction.class, 42, "DIVR");
        register(DiviInstruction.class, 43, "DIVI");
        register(ModrInstruction.class, 44, "MODR");
        register(ModiInstruction.class, 45, "MODI");

        // --- Bitweise Operationen ---
        register(NadrInstruction.class, 5, "NADR");
        register(NadiInstruction.class, 32, "NADI");
        register(AndrInstruction.class, 46, "ANDR");
        register(AndiInstruction.class, 47, "ANDI");
        register(OrrInstruction.class, 48, "ORR");
        register(OriInstruction.class, 49, "ORI");
        register(XorrInstruction.class, 50, "XORR");
        register(XoriInstruction.class, 51, "XORI");
        register(NotInstruction.class, 52, "NOT");
        register(ShliInstruction.class, 53, "SHLI");
        register(ShriInstruction.class, 54, "SHRI");

        // --- Kontrollfluss ---
        register(JmprInstruction.class, 10, "JMPR");
        register(JmpiInstruction.class, 20, "JMPI");
        register(CallInstruction.class, 34, "CALL");
        register(RetInstruction.class, 35, "RET");

        // --- Bedingungen ---
        register(IfrInstruction.class, 7, "IFR");
        register(IfrInstruction.class, 8, "LTR");
        register(IfrInstruction.class, 9, "GTR");
        register(IfiInstruction.class, 24, "IFI");
        register(IfiInstruction.class, 25, "LTI");
        register(IfiInstruction.class, 26, "GTI");
        register(IftiInstruction.class, 29, "IFTI");
        register(IftrInstruction.class, 33, "IFTR");

        // --- Welt & Zustand ---
        register(TurnInstruction.class, 11, "TURN");
        register(SeekInstruction.class, 12, "SEEK");
        register(SyncInstruction.class, 13, "SYNC");
        register(PeekInstruction.class, 14, "PEEK");
        register(PokeInstruction.class, 15, "POKE");
        register(ScanInstruction.class, 16, "SCAN");
        register(NrgInstruction.class, 17, "NRG");
        register(ForkInstruction.class, 18, "FORK");
        register(DiffInstruction.class, 19, "DIFF");
        register(PosInstruction.class, 21, "POS");
        register(RandInstruction.class, 55, "RAND");
        register(PekiInstruction.class, 56, "PEKI");
        register(PokiInstruction.class, 57, "POKI");
        register(SekiInstruction.class, 59, "SEKI"); // NEU
        register(NopInstruction.class, 0, "NOP");
    }

    private static void register(Class<? extends Instruction> instructionClass, int id, String name) {
        try {
            Field lengthField = instructionClass.getField("LENGTH");
            int length = lengthField.getInt(null);

            Method planMethod = instructionClass.getMethod("plan", Organism.class, World.class);
            BiFunction<Organism, World, Instruction> planner = (org, world) -> {
                try {
                    return (Instruction) planMethod.invoke(null, org, world);
                } catch (Exception e) { throw new RuntimeException("Fehler beim Aufruf der plan-Methode für " + name, e.getCause()); }
            };

            Method assembleMethod = instructionClass.getMethod("assemble", String[].class, Map.class, Map.class);
            AssemblerPlanner assembler = (args, regMap, lblMap) -> {
                try {
                    return (AssemblerOutput) assembleMethod.invoke(null, args, regMap, lblMap);
                } catch (Exception e) {
                    if (e instanceof InvocationTargetException ite && ite.getTargetException() instanceof IllegalArgumentException) {
                        throw (IllegalArgumentException) ite.getTargetException();
                    }
                    throw new RuntimeException("Fehler beim Aufruf der assemble-Methode für " + name, e);
                }
            };

            registerInstruction(instructionClass, id, name, length, planner, assembler);

        } catch (Exception e) {
            throw new RuntimeException("Fehler beim Registrieren der Instruktion: " + instructionClass.getSimpleName(), e);
        }
    }

    private static void registerInstruction(Class<? extends Instruction> instructionClass, int id, String name, int length,
                                            BiFunction<Organism, World, Instruction> planner, AssemblerPlanner assembler) {
        String upperCaseName = name.toUpperCase();
        int fullId = id | Config.TYPE_CODE;
        if (REGISTERED_INSTRUCTIONS_BY_ID.containsKey(fullId) && !instructionClass.equals(REGISTERED_INSTRUCTIONS_BY_ID.get(fullId))) {
            throw new IllegalArgumentException("Instruktions-ID " + id + " bereits registriert für eine andere Klasse.");
        }
        if (NAME_TO_ID.containsKey(upperCaseName) && !NAME_TO_ID.get(upperCaseName).equals(fullId)) {
            throw new IllegalArgumentException("Instruktions-Name '" + name + "' bereits registriert.");
        }
        REGISTERED_INSTRUCTIONS_BY_ID.put(fullId, instructionClass);
        NAME_TO_ID.put(upperCaseName, fullId);
        ID_TO_NAME.put(fullId, name);
        ID_TO_LENGTH.put(fullId, length);
        REGISTERED_PLANNERS_BY_ID.put(fullId, planner);
        REGISTERED_ASSEMBLERS_BY_ID.put(fullId, assembler);
    }

    public final Organism getOrganism() {
        return this.organism;
    }

    public final String getName() {
        return ID_TO_NAME.getOrDefault(this.fullOpcodeId, "UNKNOWN");
    }

    public final int getLength() {
        return ID_TO_LENGTH.getOrDefault(this.fullOpcodeId, 1);
    }

    public abstract void execute(Simulation simulation);
    public int getCost(Organism organism, World world, List<Integer> rawArguments) { return 1; }

    public static String getInstructionNameById(int id) { return ID_TO_NAME.getOrDefault(id, "UNKNOWN"); }
    public static int getInstructionLengthById(int id) { return ID_TO_LENGTH.getOrDefault(id, 1); }
    public static Integer getInstructionIdByName(String name) { return NAME_TO_ID.get(name.toUpperCase()); }
    public static BiFunction<Organism, World, Instruction> getPlannerById(int id) { return REGISTERED_PLANNERS_BY_ID.get(id); }
    public static AssemblerPlanner getAssemblerById(int id) { return REGISTERED_ASSEMBLERS_BY_ID.get(id); }
    protected static void registerArgumentTypes(int instructionId, Map<Integer, ArgumentType> types) { ARGUMENT_TYPES_BY_ID.put(instructionId | Config.TYPE_CODE, types); }
    public static ArgumentType getArgumentTypeFor(int opcodeFullId, int argIndex) { return ARGUMENT_TYPES_BY_ID.getOrDefault(opcodeFullId, Map.of()).getOrDefault(argIndex, ArgumentType.LITERAL); }

    @FunctionalInterface
    public interface AssemblerPlanner {
        AssemblerOutput apply(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap);
    }

    protected boolean executedInTick = false;
    public enum ConflictResolutionStatus { NOT_APPLICABLE, WON_EXECUTION, LOST_TARGET_OCCUPIED, LOST_TARGET_EMPTY, LOST_LOWER_ID_WON, LOST_OTHER_REASON }
    protected ConflictResolutionStatus conflictStatus = ConflictResolutionStatus.NOT_APPLICABLE;
    public boolean isExecutedInTick() { return executedInTick; }
    public void setExecutedInTick(boolean executedInTick) { this.executedInTick = executedInTick; }
    public ConflictResolutionStatus getConflictStatus() { return conflictStatus; }
    public void setConflictStatus(ConflictResolutionStatus conflictStatus) { this.conflictStatus = conflictStatus; }
}