// in: src/main/java/org/evochora/runtime/isa/Instruction.java

package org.evochora.runtime.isa;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.instructions.ArithmeticInstruction;
import org.evochora.runtime.isa.instructions.BitwiseInstruction;
import org.evochora.runtime.isa.instructions.ConditionalInstruction;
import org.evochora.runtime.isa.instructions.ControlFlowInstruction;
import org.evochora.runtime.isa.instructions.DataInstruction;
import org.evochora.runtime.isa.instructions.EnvironmentInteractionInstruction;
import org.evochora.runtime.isa.instructions.LocationInstruction;
import org.evochora.runtime.isa.instructions.NopInstruction;
import org.evochora.runtime.isa.instructions.StackInstruction;
import org.evochora.runtime.isa.instructions.StateInstruction;
import org.evochora.runtime.isa.instructions.VectorInstruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;

import static org.evochora.runtime.isa.Family.*;

/**
 * The abstract base class for all instructions in the Evochora VM.
 * This class is now free from legacy compiler dependencies and focuses
 * exclusively on runtime logic.
 */
public abstract class Instruction {

    /**
     * A public record describing the properties of a registered instruction.
     */
    public record InstructionInfo(int opcodeId, String name, Class<? extends Instruction> family) {}

    private static List<InstructionInfo> INSTRUCTION_INFO_CACHE = null;

    protected final Organism organism;
    protected final int fullOpcodeId;

    /**
     * Defines the possible sources for an instruction's operands.
     */
    public enum OperandSource { REGISTER, IMMEDIATE, STACK, VECTOR, LABEL, LOCATION_REGISTER }

    /**
     * Represents a resolved operand, containing its value and raw source ID.
     * @param value The resolved value of the operand.
     * @param rawSourceId The raw source ID (e.g., register number).
     */
    public record Operand(Object value, int rawSourceId) {}

    // Runtime Registries
    private static final Map<Integer, Class<? extends Instruction>> REGISTERED_INSTRUCTIONS_BY_ID = new HashMap<>();
    private static final Map<String, Integer> NAME_TO_ID = new HashMap<>();
    private static final Map<Integer, String> ID_TO_NAME = new HashMap<>();
    private static final Map<Integer, Integer> ID_TO_LENGTH = new HashMap<>();
    private static final Map<Integer, BiFunction<Organism, Environment, Instruction>> REGISTERED_PLANNERS_BY_ID = new HashMap<>();
    protected static final Map<Integer, List<OperandSource>> OPERAND_SOURCES = new HashMap<>();
    private static final Map<Integer, InstructionSignature> SIGNATURES_BY_ID = new HashMap<>();

    /**
     * Returns a list of public information records for all registered instructions.
     * This provides a stable, abstract way for external tools to inspect the instruction set.
     *
     * @return An unmodifiable list of {@link InstructionInfo} records.
     */
    public static List<InstructionInfo> getInstructionSetInfo() {
        if (INSTRUCTION_INFO_CACHE == null) { // Simple lazy initialization
            init(); // Ensure instructions are registered
            List<InstructionInfo> info = new ArrayList<>();
            for (Integer opcodeId : REGISTERED_INSTRUCTIONS_BY_ID.keySet()) {
                Class<? extends Instruction> implClass = REGISTERED_INSTRUCTIONS_BY_ID.get(opcodeId);
                String name = ID_TO_NAME.get(opcodeId);
                
                // Find the base "family" class (e.g., ArithmeticInstruction) by traversing up the class hierarchy.
                Class<? extends Instruction> family = implClass;
                while (family.getSuperclass() != Instruction.class && family.getSuperclass() != null && Instruction.class.isAssignableFrom(family.getSuperclass())) {
                    @SuppressWarnings("unchecked")
                    Class<? extends Instruction> superClass = (Class<? extends Instruction>) family.getSuperclass();
                    family = superClass;
                }
                info.add(new InstructionInfo(opcodeId, name, family));
            }
            INSTRUCTION_INFO_CACHE = Collections.unmodifiableList(info);
        }
        return INSTRUCTION_INFO_CACHE;
    }

    /**
     * Retrieves the implementing class for a given full opcode ID.
     *
     * @param opcodeId The full opcode ID of the instruction.
     * @return The {@code Class} object representing the instruction, or {@code null} if not found.
     */
    public static Class<? extends Instruction> getInstructionClassById(int opcodeId) {
        return REGISTERED_INSTRUCTIONS_BY_ID.get(opcodeId);
    }

    /**
     * Base address for procedure registers.
     */
    public static final int PR_BASE = 1000;
    /**
     * Base address for formal parameter registers.
     */
    public static final int FPR_BASE = 2000;

    /**
     * Constructs a new instruction.
     * @param organism The organism executing the instruction.
     * @param fullOpcodeId The full opcode ID of the instruction.
     */
    public Instruction(Organism organism, int fullOpcodeId) {
        this.organism = organism;
        this.fullOpcodeId = fullOpcodeId;
    }

    /**
     * Reads an operand's value from the organism.
     * @param id The ID of the operand to read.
     * @return The value of the operand.
     */
    protected Object readOperand(int id) {
        return organism.readOperand(id);
    }

    /**
     * Writes a value to an operand in the organism.
     * @param id The ID of the operand to write to.
     * @param value The value to write.
     * @return true if the write was successful, false otherwise.
     */
    protected boolean writeOperand(int id, Object value) {
        return organism.writeOperand(id, value);
    }

    /**
     * Resolves the operands for this instruction based on their sources.
     * <p>
     * This method is <b>idempotent</b>: the first call resolves and caches the operands,
     * subsequent calls return the cached values. This allows safe calling from multiple
     * phases (Plan, Resolve, Plugins, Execute) without side effects.
     * <p>
     * <b>Important:</b> For STACK operands, this method only <i>peeks</i> (reads without removing).
     * The actual stack pops are deferred to {@link #commitStackReads()}, which must be called
     * in the Execute phase for instructions that are actually executed.
     *
     * @param environment The environment in which the instruction is executed.
     * @return A list of resolved operands (cached after first call).
     */
    public List<Operand> resolveOperands(Environment environment) {
        // Return cached operands if already resolved (idempotent)
        if (this.cachedOperands != null) {
            return this.cachedOperands;
        }

        List<Operand> resolved = new ArrayList<>();
        List<OperandSource> sources = OPERAND_SOURCES.get(fullOpcodeId);
        if (sources == null) {
            this.cachedOperands = resolved;
            return resolved;
        }

        int[] currentIp = organism.getIpBeforeFetch();

        // For STACK operands: use iterator to peek without popping
        Iterator<Object> stackIterator = organism.getDataStack().iterator();

        for (OperandSource source : sources) {
            switch (source) {
                case STACK:
                    // PEEK via iterator - no side effects!
                    // The actual pop() happens in commitStackReads() during Execute phase
                    if (!stackIterator.hasNext()) {
                        // Stack underflow - return empty list, instruction will handle error later
                        this.cachedOperands = new ArrayList<>();
                        return this.cachedOperands;
                    }
                    Object val = stackIterator.next();
                    resolved.add(new Operand(val, -1));
                    this.stackPeekCount++;
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
                    int dims = environment.getShape().length;
                    int[] vec = new int[dims];
                    for(int i=0; i<dims; i++) {
                        Organism.FetchResult res = organism.fetchSignedArgument(currentIp, environment);
                        vec[i] = res.value();
                        currentIp = res.nextIp();
                    }
                    resolved.add(new Operand(vec, -1));
                    break;
                }
                case LABEL: {
                    int dims = environment.getShape().length;
                    int[] delta = new int[dims];
                    for(int i=0; i<dims; i++) {
                        Organism.FetchResult res = organism.fetchSignedArgument(currentIp, environment);
                        delta[i] = res.value();
                        currentIp = res.nextIp();
                    }
                    resolved.add(new Operand(delta, -1));
                    break;
                }
                case LOCATION_REGISTER: {
                    Organism.FetchResult arg = organism.fetchArgument(currentIp, environment);
                    int regId = Molecule.fromInt(arg.value()).toScalarValue();
                    // LOCATION_REGISTER operands use rawSourceId() directly (no readOperand)
                    resolved.add(new Operand(null, regId)); // Value resolved in LocationInstruction
                    currentIp = arg.nextIp();
                    break;
                }
            }
        }
        this.cachedOperands = resolved;
        return resolved;
    }

    /**
     * Commits the stack reads that were peeked during {@link #resolveOperands(Environment)}.
     * <p>
     * This method performs the actual {@code pop()} operations on the data stack.
     * It must be called <b>only once</b>, and <b>only in the Execute phase</b> for
     * instructions that are actually executed (i.e., won conflict resolution).
     * <p>
     * For instructions that lost conflict resolution, this method should NOT be called,
     * leaving the stack unchanged so the instruction can be retried in the next tick.
     */
    public void commitStackReads() {
        for (int i = 0; i < this.stackPeekCount; i++) {
            organism.getDataStack().pop();
        }
    }

    /**
     * Executes the instruction.
     * @param context The execution context.
     * @param artifact The program artifact.
     */
    public abstract void execute(ExecutionContext context, ProgramArtifact artifact);

    /**
     * Gets the energy cost of executing this instruction.
     * @param organism The organism executing the instruction.
     * @param environment The environment.
     * @param rawArguments The raw arguments of the instruction.
     * @return The energy cost.
     */
    public int getCost(Organism organism, Environment environment, List<Integer> rawArguments) {
        return 1;
    }

    /**
     * Initializes the instruction set by registering all instruction families.
     * Each instruction class is responsible for registering its own opcodes.
     */
    public static void init() {
        NopInstruction.register(SPECIAL);
        ArithmeticInstruction.register(ARITHMETIC);
        BitwiseInstruction.register(BITWISE);
        DataInstruction.register(DATA);
        StackInstruction.register(DATA);  // Stack operations are part of DATA family
        ConditionalInstruction.register(CONDITIONAL);
        ControlFlowInstruction.register(CONTROL);
        EnvironmentInteractionInstruction.register(ENVIRONMENT);
        StateInstruction.register(STATE);
        LocationInstruction.register(LOCATION);
        VectorInstruction.register(VECTOR);
    }

    // ========== Registration API for Instruction Subclasses ==========

    private static final int DEFAULT_VECTOR_DIMS = 2;

    /**
     * Registers an instruction opcode. Called by instruction subclasses in their register() method.
     *
     * @param familyClass the instruction class (e.g., ArithmeticInstruction.class)
     * @param family the family ID from {@link Family}
     * @param operation the operation number within the family
     * @param variant the variant from {@link Variant}
     * @param name the instruction mnemonic (e.g., "ADDR")
     * @param sources the operand sources for this instruction
     */
    protected static void registerOp(Class<? extends Instruction> familyClass, int family, int operation,
                                     int variant, String name, OperandSource... sources) {
        int opcodeId = OpcodeId.compute(family, operation, variant);
        registerFamily(familyClass, java.util.Map.of(opcodeId, name), java.util.List.of(sources));
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
                } else if (s == OperandSource.LOCATION_REGISTER) {
                    length++;
                    argTypesForSignature.add(InstructionArgumentType.LOCATION_REGISTER);
                } else if (s == OperandSource.IMMEDIATE) {
                    length++;
                    argTypesForSignature.add(InstructionArgumentType.LITERAL);
                } else if (s == OperandSource.VECTOR) {
                    length += DEFAULT_VECTOR_DIMS;
                    argTypesForSignature.add(InstructionArgumentType.VECTOR);
                } else if (s == OperandSource.LABEL) {
                    length += DEFAULT_VECTOR_DIMS;
                    argTypesForSignature.add(InstructionArgumentType.LABEL);
                }
            }
            InstructionSignature signature = new InstructionSignature(argTypesForSignature);

            for (Map.Entry<Integer, String> entry : variants.entrySet()) {
                int id = entry.getKey();
                String name = entry.getValue();

                BiFunction<Organism, Environment, Instruction> planner = (org, world) -> {
                    try {
                        return constructor.newInstance(org, world.getMolecule(org.getIp()).value());
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

    /**
     * Resolves a register token (e.g., "%DR0") to its corresponding integer ID.
     * @param token The register token to resolve.
     * @return An Optional containing the integer ID, or empty if the token is invalid.
     */
    public static Optional<Integer> resolveRegToken(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String u = token.toUpperCase();
        try {
            if (u.startsWith("%DR")) {
                int regNum = Integer.parseInt(u.substring(3));
                return Optional.of(regNum);
            }
            if (u.startsWith("%PR")) {
                int regNum = Integer.parseInt(u.substring(3));
                return Optional.of(PR_BASE + regNum);
            }
            if (u.startsWith("%FPR")) {
                int regNum = Integer.parseInt(u.substring(4));
                return Optional.of(FPR_BASE + regNum);
            }
            if (u.startsWith("%LR")) {
                int regNum = Integer.parseInt(u.substring(3));
                return Optional.of(regNum); // LR-Register haben direkte Indizes 0-3
            }
        } catch (NumberFormatException ignore) {
            // Falls through to empty Optional if, e.g., "%DR" has no number.
        }
        return Optional.empty();
    }

    // --- Static Getters for Runtime Information ---

    /**
     * Gets the length of the instruction in the environment.
     * @return The length of the instruction.
     */
    public int getLength() { return getInstructionLengthById(this.fullOpcodeId); }

    /**
     * Gets the length of the instruction in the given environment.
     * @param env The environment.
     * @return The length of the instruction.
     */
    public int getLength(Environment env) { return getInstructionLengthById(this.fullOpcodeId, env); }

    /**
     * Gets the organism executing this instruction.
     * @return The organism.
     */
    public final Organism getOrganism() { return this.organism; }

    /**
     * Gets the name of this instruction.
     * @return The instruction's name.
     */
    public final String getName() { return ID_TO_NAME.getOrDefault(this.fullOpcodeId, "UNKNOWN"); }

    /**
     * Gets the full opcode ID of this instruction.
     * @return The full opcode ID.
     */
    public int getFullOpcodeId() { return this.fullOpcodeId; }

    /**
     * Gets the name of an instruction by its ID.
     * @param id The instruction ID.
     * @return The name of the instruction.
     */
    public static String getInstructionNameById(int id) { return ID_TO_NAME.getOrDefault(id, "UNKNOWN"); }
    
    /**
     * Returns an unmodifiable view of all registered instruction IDs and names.
     * <p>
     * This is useful for serializing the complete opcode mapping to clients
     * (e.g., in metadata responses for the visualizer API).
     * <p>
     * <strong>Note:</strong> {@link #init()} must be called before this method
     * returns meaningful data.
     *
     * @return Unmodifiable map of opcode ID to instruction name (e.g., {0x00: "NOP", 0x10: "ADD", ...})
     */
    public static java.util.Map<Integer, String> getAllInstructions() {
        return java.util.Collections.unmodifiableMap(ID_TO_NAME);
    }

    /**
     * Gets the length of an instruction by its ID.
     * @param id The instruction ID.
     * @return The length of the instruction.
     */
    public static int getInstructionLengthById(int id) { return ID_TO_LENGTH.getOrDefault(id, 1); }

    /**
     * Gets the length of an instruction by its ID in a given environment.
     * @param id The instruction ID.
     * @param env The environment.
     * @return The length of the instruction.
     */
    public static int getInstructionLengthById(int id, Environment env) {
        List<OperandSource> sources = OPERAND_SOURCES.get(id);
        if (sources == null) return 1;
        int length = 1;
        int dims = env.getShape().length;
        for (OperandSource s : sources) {
            if (s == OperandSource.REGISTER || s == OperandSource.IMMEDIATE || s == OperandSource.LOCATION_REGISTER || s == OperandSource.STACK) {
                // For STACK we assume no encoded operand in code
                // LOCATION_REGISTER is encoded like REGISTER (one slot)
                if (s != OperandSource.STACK) length++;
            } else if (s == OperandSource.VECTOR || s == OperandSource.LABEL) {
                length += dims;
            }
        }
        return length;
    }

    /**
     * Gets the ID of an instruction by its name.
     * @param name The name of the instruction.
     * @return The instruction ID.
     */
    public static Integer getInstructionIdByName(String name) { return NAME_TO_ID.get(name.toUpperCase()); }

    /**
     * Gets the planner function for an instruction by its ID.
     * @param id The instruction ID.
     * @return The planner function.
     */
    public static BiFunction<Organism, Environment, Instruction> getPlannerById(int id) { return REGISTERED_PLANNERS_BY_ID.get(id); }

    /**
     * Gets the signature of an instruction by its ID.
     * @param id The instruction ID.
     * @return An Optional containing the instruction signature.
     */
    public static Optional<InstructionSignature> getSignatureById(int id) { return Optional.ofNullable(SIGNATURES_BY_ID.get(id)); }


    // --- Conflict Resolution Logic ---

    protected boolean executedInTick = false;

    /**
     * Represents the status of an instruction after conflict resolution.
     */
    public enum ConflictResolutionStatus { NOT_APPLICABLE, WON_EXECUTION, LOST_TARGET_OCCUPIED, LOST_TARGET_EMPTY, LOST_LOWER_ID_WON, LOST_OTHER_REASON }
    protected ConflictResolutionStatus conflictStatus = ConflictResolutionStatus.NOT_APPLICABLE;
    
    /** Cached operands - resolved once, returned on subsequent calls (idempotent). */
    private List<Operand> cachedOperands = null;

    /** Number of stack values that were peeked during resolveOperands() and need to be popped in commitStackReads(). */
    private int stackPeekCount = 0;


    /**
     * Checks if the instruction was executed in the current tick.
     * @return true if executed, false otherwise.
     */
    public boolean isExecutedInTick() { return executedInTick; }

    /**
     * Sets whether the instruction was executed in the current tick.
     * @param executedInTick true if executed, false otherwise.
     */
    public void setExecutedInTick(boolean executedInTick) { this.executedInTick = executedInTick; }

    /**
     * Gets the conflict resolution status of the instruction.
     * @return The conflict resolution status.
     */
    public ConflictResolutionStatus getConflictStatus() { return conflictStatus; }

    /**
     * Sets the conflict resolution status of the instruction.
     * @param conflictStatus The new conflict resolution status.
     */
    public void setConflictStatus(ConflictResolutionStatus conflictStatus) { this.conflictStatus = conflictStatus; }
}