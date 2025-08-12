package org.evochora.runtime.isa;

import org.evochora.app.setup.Config;
import org.evochora.app.Simulation;
import org.evochora.compiler.internal.legacy.ArgumentType;
import org.evochora.compiler.internal.legacy.AssemblerOutput;
import org.evochora.runtime.isa.instructions.*;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.World;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.BiFunction;

public abstract class Instruction {

    protected final Organism organism;
    protected final int fullOpcodeId;

    public enum OperandSource { REGISTER, IMMEDIATE, STACK, VECTOR, LABEL }
    public record Operand(Object value, int rawSourceId) {}

    private static final Map<Integer, Class<? extends Instruction>> REGISTERED_INSTRUCTIONS_BY_ID = new HashMap<>();
    private static final Map<String, Integer> NAME_TO_ID = new HashMap<>();
    private static final Map<Integer, String> ID_TO_NAME = new HashMap<>();
    private static final Map<Integer, Integer> ID_TO_LENGTH = new HashMap<>();
    private static final Map<Integer, BiFunction<Organism, World, Instruction>> REGISTERED_PLANNERS_BY_ID = new HashMap<>();
    private static final Map<Integer, AssemblerPlanner> REGISTERED_ASSEMBLERS_BY_ID = new HashMap<>();
    protected static final Map<Integer, List<OperandSource>> OPERAND_SOURCES = new HashMap<>();
    private static final Map<Integer, Map<Integer, ArgumentType>> ARGUMENT_TYPES_BY_ID = new HashMap<>();


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

    public List<Operand> resolveOperands(World world) {
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
                    Organism.FetchResult arg = organism.fetchArgument(currentIp, world);
                    int regId = Molecule.fromInt(arg.value()).toScalarValue();
                    resolved.add(new Operand(readOperand(regId), regId));
                    currentIp = arg.nextIp();
                    break;
                }
                case IMMEDIATE: {
                    Organism.FetchResult arg = organism.fetchArgument(currentIp, world);
                    resolved.add(new Operand(arg.value(), -1));
                    currentIp = arg.nextIp();
                    break;
                }
                case VECTOR: {
                    int[] vec = new int[Config.WORLD_DIMENSIONS];
                    for(int i=0; i<Config.WORLD_DIMENSIONS; i++) {
                        Organism.FetchResult res = organism.fetchSignedArgument(currentIp, world);
                        vec[i] = res.value();
                        currentIp = res.nextIp();
                    }
                    resolved.add(new Operand(vec, -1));
                    break;
                }
                case LABEL: {
                    int[] delta = new int[Config.WORLD_DIMENSIONS];
                    for(int i=0; i<Config.WORLD_DIMENSIONS; i++) {
                        Organism.FetchResult res = organism.fetchSignedArgument(currentIp, world);
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

    public static Integer resolveRegToken(String token, Map<String, Integer> registerMap) {
        String u = token.toUpperCase();
        if (u.startsWith("%DR")) {
            try { return Integer.parseInt(u.substring(3)); } catch (NumberFormatException ignore) {}
        }
        if (u.startsWith("%PR")) {
            try { return PR_BASE + Integer.parseInt(u.substring(3)); } catch (NumberFormatException ignore) {}
        }
        if (u.startsWith("%FPR")) {
            try { return FPR_BASE + Integer.parseInt(u.substring(4)); } catch (NumberFormatException ignore) {}
        }
        return registerMap.get(u);
    }

    public static void init() {
        // --- Arithmetik-Familie ---
        registerFamily(ArithmeticInstruction.class, Map.of(4, "ADDR", 6, "SUBR", 40, "MULR", 42, "DIVR", 44, "MODR"), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(ArithmeticInstruction.class, Map.of(30, "ADDI", 31, "SUBI", 41, "MULI", 43, "DIVI", 45, "MODI"), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(ArithmeticInstruction.class, Map.of(70, "ADDS", 71, "SUBS", 72, "MULS", 73, "DIVS", 74, "MODS"), List.of(OperandSource.STACK, OperandSource.STACK));

        // --- Bitwise-Familie ---
        registerFamily(BitwiseInstruction.class, Map.of(5, "NADR", 46, "ANDR", 48, "ORR", 50, "XORR"), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(BitwiseInstruction.class, Map.of(32, "NADI", 47, "ANDI", 49, "ORI", 51, "XORI", 53, "SHLI", 54, "SHRI"), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(BitwiseInstruction.class, Map.of(78, "NADS", 75, "ANDS", 76, "ORS", 77, "XORS", 80, "SHLS", 81, "SHRS"), List.of(OperandSource.STACK, OperandSource.STACK));
        registerFamily(BitwiseInstruction.class, Map.of(52, "NOT"), List.of(OperandSource.REGISTER));
        registerFamily(BitwiseInstruction.class, Map.of(79, "NOTS"), List.of(OperandSource.STACK));

        // --- Data-Familie ---
        registerFamily(DataInstruction.class, Map.of(1, "SETI"), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(DataInstruction.class, Map.of(2, "SETR"), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(DataInstruction.class, Map.of(3, "SETV"), List.of(OperandSource.REGISTER, OperandSource.VECTOR));
        registerFamily(DataInstruction.class, Map.of(22, "PUSH"), List.of(OperandSource.REGISTER));
        registerFamily(DataInstruction.class, Map.of(23, "POP"), List.of(OperandSource.REGISTER));
        registerFamily(DataInstruction.class, Map.of(58, "PUSI"), List.of(OperandSource.IMMEDIATE));

        // --- Stack-Familie ---
        registerFamily(StackInstruction.class, Map.of(60, "DUP", 61, "SWAP", 62, "DROP", 63, "ROT"), List.of());

        // --- Conditional-Familie ---
        registerFamily(ConditionalInstruction.class, Map.of(7, "IFR", 8, "LTR", 9, "GTR", 33, "IFTR"), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(ConditionalInstruction.class, Map.of(24, "IFI", 25, "LTI", 26, "GTI", 29, "IFTI"), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(ConditionalInstruction.class, Map.of(85, "IFS", 86, "GTS", 87, "LTS", 88, "IFTS"), List.of(OperandSource.STACK, OperandSource.STACK));
        registerFamily(ConditionalInstruction.class, Map.of(93, "IFMR"), List.of(OperandSource.REGISTER));
        registerFamily(ConditionalInstruction.class, Map.of(94, "IFMI"), List.of(OperandSource.VECTOR));
        registerFamily(ConditionalInstruction.class, Map.of(95, "IFMS"), List.of(OperandSource.STACK));

        // --- ControlFlow-Familie ---
        registerFamily(ControlFlowInstruction.class, Map.of(20, "JMPI", 34, "CALL"), List.of(OperandSource.LABEL));
        registerFamily(ControlFlowInstruction.class, Map.of(10, "JMPR"), List.of(OperandSource.REGISTER));
        registerFamily(ControlFlowInstruction.class, Map.of(89, "JMPS"), List.of(OperandSource.STACK));
        registerFamily(ControlFlowInstruction.class, Map.of(35, "RET"), List.of());

        // --- KORRIGIERTE REGISTRIERUNG ---
        // WorldInteraction (POKE & PEEK)
        registerFamily(WorldInteractionInstruction.class, Map.of(15, "POKE", 14, "PEEK"), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(WorldInteractionInstruction.class, Map.of(57, "POKI", 56, "PEKI"), List.of(OperandSource.REGISTER, OperandSource.VECTOR));
        registerFamily(WorldInteractionInstruction.class, Map.of(91, "POKS"), List.of(OperandSource.STACK, OperandSource.STACK)); // POKS braucht 2
        registerFamily(WorldInteractionInstruction.class, Map.of(90, "PEKS"), List.of(OperandSource.STACK)); // PEKS braucht 1

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

        // --- NOP ---
        // Use registerFamily so the assembler planner is wired via reflection
        registerFamily(NopInstruction.class, Map.of(0, "NOP"), List.of());
    }

    private static void registerFamily(Class<? extends Instruction> familyClass, Map<Integer, String> variants, List<OperandSource> sources) {
        try {
            Constructor<? extends Instruction> constructor = familyClass.getConstructor(Organism.class, int.class);
            Method assembleMethod = familyClass.getMethod("assemble", String[].class, Map.class, Map.class, String.class);

            for (Map.Entry<Integer, String> entry : variants.entrySet()) {
                int id = entry.getKey();
                String name = entry.getValue();
                int fullId = id | Config.TYPE_CODE;

                BiFunction<Organism, World, Instruction> planner = (org, world) -> {
                    try {
                        return constructor.newInstance(org, world.getMolecule(org.getIp()).toInt());
                    } catch (Exception e) { throw new RuntimeException("Failed to plan instruction " + name, e); }
                };

                AssemblerPlanner assembler = (args, regMap, lblMap) -> {
                    try {
                        return (AssemblerOutput) assembleMethod.invoke(null, args, regMap, lblMap, name);
                    } catch (Exception e) {
                        if (e instanceof InvocationTargetException ite && ite.getTargetException() instanceof RuntimeException) throw (RuntimeException)ite.getTargetException();
                        throw new RuntimeException("Failed to assemble " + name, e);
                    }
                };

                int length = 1;
                Map<Integer, ArgumentType> argTypes = new HashMap<>();
                int argIndex = 0;
                for(OperandSource s : sources) {
                    if (s == OperandSource.REGISTER) {
                        length++;
                        argTypes.put(argIndex++, ArgumentType.REGISTER);
                    } else if (s == OperandSource.IMMEDIATE) {
                        length++;
                        argTypes.put(argIndex++, ArgumentType.LITERAL);
                    } else if (s == OperandSource.VECTOR) {
                        length += Config.WORLD_DIMENSIONS;
                        for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
                            argTypes.put(argIndex++, ArgumentType.COORDINATE);
                        }
                    } else if (s == OperandSource.LABEL) {
                        length += Config.WORLD_DIMENSIONS;
                        argTypes.put(argIndex++, ArgumentType.LABEL);
                    }
                }

                registerInstruction(familyClass, id, name, length, planner, assembler);
                OPERAND_SOURCES.put(fullId, sources);
                ARGUMENT_TYPES_BY_ID.put(fullId, argTypes);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to register instruction family " + familyClass.getSimpleName(), e);
        }
    }

    private static void register(Class<? extends Instruction> instructionClass, int id, String name) {
        // This is now just for NOP
        BiFunction<Organism, World, Instruction> planner = (org, world) -> new NopInstruction(org, 0);
        registerInstruction(instructionClass, id, name, 1, planner, null);
    }

    protected static void register(String name, Class<? extends Instruction> instructionClass, int id, List<OperandSource> sources,
                                 TargetCoordLambda targetLambda) {

        BiFunction<Organism, World, Instruction> planner = (org, world) -> {
            try {
                Instruction inst = instructionClass.getConstructor(Organism.class, int.class)
                        .newInstance(org, world.getMolecule(org.getIp()).toInt());
                if (targetLambda != null) {
                    inst.setTargetCoordLambda(targetLambda);
                }
                return inst;
            } catch (Exception e) {
                throw new RuntimeException("Failed to plan instruction " + name, e);
            }
        };

        // Determine length and arg types from sources
        int length = 1;
        Map<Integer, ArgumentType> argTypes = new HashMap<>();
        int argIndex = 0;
        for (OperandSource s : sources) {
            if (s == OperandSource.STACK) {
                // Stack operands don't increase instruction length
            } else if (s == OperandSource.IMMEDIATE) {
                length++;
                argTypes.put(argIndex++, ArgumentType.LITERAL);
            } else if (s == OperandSource.VECTOR) {
                length += Config.WORLD_DIMENSIONS;
                for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) argTypes.put(argIndex++, ArgumentType.COORDINATE);
            } else if (s == OperandSource.LABEL) {
                length += Config.WORLD_DIMENSIONS;
                argTypes.put(argIndex++, ArgumentType.LABEL);
            } else { // REGISTER
                length++;
                argTypes.put(argIndex++, ArgumentType.REGISTER);
            }
        }

        OPERAND_SOURCES.put(id | Config.TYPE_CODE, sources);
        ARGUMENT_TYPES_BY_ID.put(id | Config.TYPE_CODE, argTypes);
        registerInstruction(instructionClass, id, name, length, planner, null);
    }

    private static void registerInstruction(Class<? extends Instruction> instructionClass, int id, String name, int length,
                                            BiFunction<Organism, World, Instruction> planner, AssemblerPlanner assembler) {
        String upperCaseName = name.toUpperCase();
        int fullId = id | Config.TYPE_CODE;
        REGISTERED_INSTRUCTIONS_BY_ID.put(fullId, instructionClass);
        NAME_TO_ID.put(upperCaseName, fullId);
        ID_TO_NAME.put(fullId, name);
        ID_TO_LENGTH.put(fullId, length);
        REGISTERED_PLANNERS_BY_ID.put(fullId, planner);
        REGISTERED_ASSEMBLERS_BY_ID.put(fullId, assembler);
    }

    public abstract void execute(Simulation simulation);
    public int getCost(Organism organism, World world, List<Integer> rawArguments) { return 1; }

    public int getLength() {
        return getInstructionLengthById(this.fullOpcodeId);
    }

    public final Organism getOrganism() {
        return this.organism;
    }

    public final String getName() {
        return ID_TO_NAME.getOrDefault(this.fullOpcodeId, "UNKNOWN");
    }

    public static String getInstructionNameById(int id) { return ID_TO_NAME.getOrDefault(id, "UNKNOWN"); }
    public static int getInstructionLengthById(int id) { return ID_TO_LENGTH.getOrDefault(id, 1); }
    public static Integer getInstructionIdByName(String name) { return NAME_TO_ID.get(name.toUpperCase()); }
    public static BiFunction<Organism, World, Instruction> getPlannerById(int id) { return REGISTERED_PLANNERS_BY_ID.get(id); }
    public static AssemblerPlanner getAssemblerById(int id) { return REGISTERED_ASSEMBLERS_BY_ID.get(id); }
    public static ArgumentType getArgumentTypeFor(int opcodeFullId, int argIndex) { return ARGUMENT_TYPES_BY_ID.getOrDefault(opcodeFullId, Map.of()).getOrDefault(argIndex, ArgumentType.LITERAL); }

    @FunctionalInterface public interface AssemblerPlanner { AssemblerOutput apply(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap); }
    @FunctionalInterface public interface TargetCoordLambda { List<int[]> apply(Organism organism, World world); }

    protected TargetCoordLambda targetCoordLambda;
    protected boolean executedInTick = false;
    public enum ConflictResolutionStatus { NOT_APPLICABLE, WON_EXECUTION, LOST_TARGET_OCCUPIED, LOST_TARGET_EMPTY, LOST_LOWER_ID_WON, LOST_OTHER_REASON }
    protected ConflictResolutionStatus conflictStatus = ConflictResolutionStatus.NOT_APPLICABLE;

    public boolean isExecutedInTick() { return executedInTick; }
    public void setExecutedInTick(boolean executedInTick) { this.executedInTick = executedInTick; }
    public ConflictResolutionStatus getConflictStatus() { return conflictStatus; }
    public void setConflictStatus(ConflictResolutionStatus conflictStatus) { this.conflictStatus = conflictStatus; }
    public void setTargetCoordLambda(TargetCoordLambda lambda) { this.targetCoordLambda = lambda; }
    public List<int[]> getTargetCoordinates(World world) {
        if (this.targetCoordLambda != null) {
            return this.targetCoordLambda.apply(this.organism, world);
        }
        return Collections.emptyList();
    }
}