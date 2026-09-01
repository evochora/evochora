// in: src/main/java/org/evochora/runtime/isa/Instruction.java

package org.evochora.runtime.isa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
     * Factory for creating instruction instances without reflection.
     * Each instruction family provides a method reference to its constructor (e.g., {@code ArithmeticInstruction::new}).
     */
    @FunctionalInterface
    public interface InstructionFactory {
        /**
         * Creates a new instruction instance.
         *
         * @param organism The organism executing the instruction.
         * @param opcodeId The opcode ID of the instruction.
         * @return The new instruction instance.
         */
        Instruction create(Organism organism, int opcodeId);
    }

    /**
     * Represents a resolved operand, containing its value and raw source ID.
     * @param value The resolved value of the operand.
     * @param rawSourceId The raw source ID (e.g., register number).
     */
    public record Operand(Object value, int rawSourceId) {}

    // Runtime Registries (HashMaps for cold-path usage: registration, introspection, mutation plugins)
    private static final Map<Integer, Class<? extends Instruction>> REGISTERED_INSTRUCTIONS_BY_ID = new HashMap<>();
    private static final Map<String, Integer> NAME_TO_ID = new HashMap<>();
    private static final Map<Integer, String> ID_TO_NAME = new HashMap<>();
    private static final Map<Integer, InstructionFactory> REGISTERED_PLANNERS_BY_ID = new HashMap<>();
    protected static final Map<Integer, List<OperandSource>> OPERAND_SOURCES = new HashMap<>();
    private static final Map<Integer, InstructionSignature> SIGNATURES_BY_ID = new HashMap<>();
    private static final Map<Integer, Boolean> PARALLEL_EXECUTE_SAFE_MAP = new HashMap<>();

    // Array-based registries for O(1) hot-path lookups (populated from HashMaps during init())
    // Opcode range: Family 0-9, max opcode = 9*4096 + 63*64 + 63 = 40959
    private static final int REGISTRY_SIZE = 41000;
    private static InstructionFactory[] PLANNERS_ARRAY = new InstructionFactory[0];
    @SuppressWarnings("unchecked")
    private static List<OperandSource>[] OPERAND_SOURCES_ARRAY = new List[0];
    private static int[] INSTRUCTION_LENGTHS_BASE = new int[0];
    private static int[] INSTRUCTION_LENGTHS_DIMS_MULTIPLIER = new int[0];
    private static boolean[] PARALLEL_EXECUTE_SAFE = new boolean[0];
    private static String[] NAMES_ARRAY = new String[0];
    private static InstructionSignature[] SIGNATURES_ARRAY = new InstructionSignature[0];

    /** Shared empty array for instructions whose opcode carries no arguments. */
    private static final int[] EMPTY_RAW_ARGUMENTS = new int[0];

    /**
     * Whether {@link #init()} has registered the instruction set.
     * <p>
     * Volatile so that repeated calls can see it without taking a lock. Registration happens once,
     * while callers keep asking whether it already has — among them a database read path that asks
     * twice per organism it decodes — so the answer has to be cheap and has to stay cheap as the
     * number of asking threads grows.
     */
    private static volatile boolean initialized = false;

    /**
     * Returns a list of public information records for all registered instructions.
     * This provides a stable, abstract way for external tools to inspect the instruction set.
     *
     * @return An unmodifiable list of {@link InstructionInfo} records.
     */
    public static List<InstructionInfo> getInstructionSetInfo() {
        init();
        return INSTRUCTION_INFO_CACHE;
    }

    /**
     * Describes every registered instruction, for callers that inspect the instruction set.
     * <p>
     * Called by {@link #init()} while it holds the lock, so the description is complete before any
     * caller can reach it and is built once however many callers ask.
     *
     * @return an unmodifiable list describing the registered instructions
     */
    private static List<InstructionInfo> buildInstructionSetInfo() {
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
        return Collections.unmodifiableList(info);
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
     * Constructs a new instruction.
     * @param organism The organism executing the instruction.
     * @param fullOpcodeId The full opcode ID of the instruction.
     */
    public Instruction(Organism organism, int fullOpcodeId) {
        this.organism = organism;
        this.fullOpcodeId = fullOpcodeId;
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
     * Writes a vector value to a location register.
     * Only accepts location register banks — data register writes are rejected.
     *
     * @param id the full ID of the location register
     * @param value the vector value to write
     * @return true if the write was successful, false otherwise
     */
    protected boolean writeLocationOperand(int id, int[] value) {
        return organism.writeLocationOperand(id, value);
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

        List<OperandSource> sources = (fullOpcodeId >= 0 && fullOpcodeId < REGISTRY_SIZE)
                ? OPERAND_SOURCES_ARRAY[fullOpcodeId]
                : OPERAND_SOURCES.get(fullOpcodeId);
        if (sources == null) {
            this.rawArguments = EMPTY_RAW_ARGUMENTS;
            this.cachedOperands = List.of();
            return this.cachedOperands;
        }

        // The argument cells are fetched here, once per instruction. Operand resolution
        // below and the execution record both work from this array; nothing else reads the
        // code stream on their behalf.
        this.rawArguments = organism.getRawArgumentsFromEnvironment(getLength(environment), environment);

        List<Operand> resolved = new ArrayList<>(sources.size());
        int dims = environment.properties.getDimensions();

        // For STACK operands: use iterator to peek without popping
        Iterator<Object> stackIterator = organism.getDataStack().iterator();

        int slot = 0;
        for (OperandSource source : sources) {
            if (source == OperandSource.STACK) {
                // PEEK via iterator - no side effects!
                // The actual pop() happens in commitStackReads() during Execute phase
                if (!stackIterator.hasNext()) {
                    this.cachedOperands = new ArrayList<>();
                    return this.cachedOperands;
                }
                resolved.add(new Operand(stackIterator.next(), -1));
                this.stackPeekCount++;
                continue;
            }

            // Every remaining source occupies one argument slot; VECTOR takes one per dimension.
            int rawMol = this.rawArguments[slot++];

            switch (source) {
                case REGISTER -> {
                    int regId = Molecule.extractSignedValue(rawMol);
                    resolved.add(new Operand(organism.readOperand(regId), regId));
                }
                case IMMEDIATE -> {
                    resolved.add(new Operand(rawMol, -1));
                }
                case LOCATION_REGISTER -> {
                    int regId = Molecule.extractSignedValue(rawMol);
                    // Probes the id exactly like the REGISTER branch: an invalid id marks the
                    // instruction failed during planning, so later readers of register values
                    // (the instruction body, observers) find the failure already recorded and
                    // never produce it themselves. The operand carries only the id, because
                    // location registers are read and written by the instruction itself.
                    organism.readOperand(regId);
                    resolved.add(new Operand(null, regId));
                }
                case VECTOR -> {
                    int[] vec = new int[dims];
                    vec[0] = Molecule.extractSignedValue(rawMol);
                    for (int d = 1; d < dims; d++) {
                        vec[d] = Molecule.extractSignedValue(this.rawArguments[slot++]);
                    }
                    resolved.add(new Operand(vec, -1));
                }
                case LABEL -> {
                    int labelHash = rawMol & Config.VALUE_MASK;
                    resolved.add(new Operand(labelHash, -1));
                }
                default -> {}
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
     * Returns the raw molecule values of this instruction's argument slots, in the order they
     * occupy the code stream.
     * <p>
     * The array is filled by {@link #resolveOperands(Environment)} and must not be modified: it is
     * the same array the operand resolution read from, and it is handed on to the execution record
     * without a copy.
     *
     * @return the argument slots, empty for an instruction without arguments
     * @throws IllegalStateException if the operands of this instruction have not been resolved yet
     */
    public int[] getRawArguments() {
        if (this.rawArguments == null) {
            throw new IllegalStateException(
                    "Raw arguments requested before resolveOperands() ran for opcode " + fullOpcodeId);
        }
        return this.rawArguments;
    }

    // ========== Fuzzy Jump Helper Methods ==========

    /**
     * Resolves a label hash operand from the environment.
     * <p>
     * Used by fuzzy jump instructions (JMPI, CALL, SKJI, etc.) to read the
     * 20-bit label hash value from the code stream.
     *
     * @param currentIp The current instruction pointer position.
     * @param environment The environment to fetch from.
     * @return The label hash value masked to {@link Config#VALUE_MASK}.
     */
    protected int resolveLabelHash(int[] currentIp, Environment environment) {
        Organism.FetchResult res = organism.fetchSignedArgument(currentIp, environment);
        return res.value() & Config.VALUE_MASK;
    }

    /**
     * Resolves a label hash to absolute coordinates using fuzzy matching.
     * <p>
     * Uses the environment's LabelIndex to find the best matching LABEL molecule
     * based on Hamming distance and physical distance, with preference for labels
     * owned by the same organism.
     *
     * @param labelHash The 20-bit hash value of the target label.
     * @param callerCoords The coordinates to use for distance calculation
     *                     (e.g., organism's IP for JMPI, or active DP for SKJI).
     * @param organism The organism executing the instruction (ownership checks and random source).
     * @param environment The environment containing the LabelIndex.
     * @return The absolute coordinates of the best matching label, or null if no match found.
     */
    protected int[] resolveLabelTarget(int labelHash, int[] callerCoords,
                                       Organism organism, Environment environment) {
        int targetFlatIndex = environment.getLabelIndex().findTarget(
                labelHash,
                organism.getId(),
                callerCoords,
                environment,
                organism.getRandom()
        );
        if (targetFlatIndex < 0) {
            return null;
        }
        return environment.getCoordinateFromIndex(targetFlatIndex);
    }

    /**
     * Validates that the target coordinates are accessible to the organism.
     * The target must be either unowned or owned by the organism itself.
     *
     * @param targetCoords The coordinates to check.
     * @param organism     The organism performing the access.
     * @param environment  The environment containing ownership data.
     * @param instructionName The instruction name for the error message.
     * @return {@code true} if the target is accessible, {@code false} if the instruction failed.
     */
    protected boolean validateOwnership(int[] targetCoords, Organism organism,
                                        Environment environment, String instructionName) {
        int ownerIdAtTarget = environment.getOwnerId(targetCoords);
        if (ownerIdAtTarget != 0 && !organism.isCellAccessible(ownerIdAtTarget)) {
            organism.instructionFailed(instructionName + ": Target cell is owned by another organism.");
            return false;
        }
        return true;
    }

    /**
     * Executes the instruction.
     * @param context The execution context.
     */
    public abstract void execute(ExecutionContext context);

    /**
     * Initializes the instruction set by registering all instruction families.
     * Each instruction class is responsible for registering its own opcodes.
     * <p>
     * Registration happens on the first call and every later call returns without touching the
     * registries. That is what makes it safe to call from anywhere: {@link #buildArrayRegistries()}
     * replaces the array registries the virtual machine reads on every instruction, assigning a new
     * empty array and filling it afterwards, so repeating it while a simulation runs would let a
     * running thread read an array that is momentarily empty and see a registered instruction as
     * unregistered.
     * <p>
     * Callers must reach this method before starting any thread that executes instructions. Doing so
     * establishes the happens-before edge that makes the filled registries visible to that thread,
     * which is why the readers need no synchronization of their own.
     */
    public static void init() {
        if (initialized) {
            return;
        }
        synchronized (Instruction.class) {
            if (initialized) {
                return;
            }

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

            buildArrayRegistries();
            INSTRUCTION_INFO_CACHE = buildInstructionSetInfo();

            // Set last: the check above the lock reads this flag without holding it, so a thread
            // that sees it set must find every registry complete. Writing it earlier would offer
            // that thread a registry still being filled.
            initialized = true;
        }
    }

    /**
     * Populates array-based registries from the HashMaps for O(1) hot-path lookups.
     * Called once at the end of {@link #init()}.
     */
    @SuppressWarnings("unchecked")
    private static void buildArrayRegistries() {
        PLANNERS_ARRAY = new InstructionFactory[REGISTRY_SIZE];
        OPERAND_SOURCES_ARRAY = new List[REGISTRY_SIZE];
        INSTRUCTION_LENGTHS_BASE = new int[REGISTRY_SIZE];
        INSTRUCTION_LENGTHS_DIMS_MULTIPLIER = new int[REGISTRY_SIZE];

        for (var entry : REGISTERED_PLANNERS_BY_ID.entrySet()) {
            int id = entry.getKey();
            if (id >= 0 && id < REGISTRY_SIZE) {
                PLANNERS_ARRAY[id] = entry.getValue();
            }
        }

        for (var entry : OPERAND_SOURCES.entrySet()) {
            int id = entry.getKey();
            if (id >= 0 && id < REGISTRY_SIZE) {
                List<OperandSource> sources = entry.getValue();
                OPERAND_SOURCES_ARRAY[id] = sources;

                int base = 1;
                int dimsMultiplier = 0;
                for (OperandSource s : sources) {
                    if (s == OperandSource.VECTOR) {
                        dimsMultiplier++;
                    } else if (s != OperandSource.STACK) {
                        base++;
                    }
                }
                INSTRUCTION_LENGTHS_BASE[id] = base;
                INSTRUCTION_LENGTHS_DIMS_MULTIPLIER[id] = dimsMultiplier;
            }
        }

        PARALLEL_EXECUTE_SAFE = new boolean[REGISTRY_SIZE];
        // Default false (conservative): instructions must explicitly opt in to parallel execution
        for (var entry : PARALLEL_EXECUTE_SAFE_MAP.entrySet()) {
            int id = entry.getKey();
            if (id >= 0 && id < REGISTRY_SIZE) {
                PARALLEL_EXECUTE_SAFE[id] = entry.getValue();
            }
        }

        NAMES_ARRAY = new String[REGISTRY_SIZE];
        for (var entry : ID_TO_NAME.entrySet()) {
            int id = entry.getKey();
            if (id >= 0 && id < REGISTRY_SIZE) {
                NAMES_ARRAY[id] = entry.getValue();
            }
        }

        SIGNATURES_ARRAY = new InstructionSignature[REGISTRY_SIZE];
        for (var entry : SIGNATURES_BY_ID.entrySet()) {
            int id = entry.getKey();
            if (id >= 0 && id < REGISTRY_SIZE) {
                SIGNATURES_ARRAY[id] = entry.getValue();
            }
        }
    }

    // ========== Registration API for Instruction Subclasses ==========

    /**
     * Registers an instruction opcode with explicit parallel-execute safety.
     * Instructions marked as unsafe are environment-modifying and will be executed sequentially.
     * <p>
     * <b>Thread safety:</b> Must only be called during single-threaded initialization ({@link #init()}).
     *
     * @param familyClass the instruction class (e.g., ArithmeticInstruction.class)
     * @param factory the factory for creating instruction instances (e.g., {@code ArithmeticInstruction::new})
     * @param family the family ID from {@link Family}
     * @param operation the operation number within the family
     * @param variant the variant from {@link Variant}
     * @param name the instruction mnemonic (e.g., "ADDR")
     * @param parallelExecuteSafe whether this instruction can safely execute in parallel (no shared environment writes)
     * @param sources the operand sources for this instruction
     * @throws IllegalArgumentException if family, operation, or variant values are out of range
     */
    protected static void registerOp(Class<? extends Instruction> familyClass, InstructionFactory factory,
                                     int family, int operation,
                                     int variant, String name,
                                     boolean parallelExecuteSafe, OperandSource... sources) {
        int fullId = OpcodeId.compute(family, operation, variant) | Config.TYPE_CODE;
        PARALLEL_EXECUTE_SAFE_MAP.put(fullId, parallelExecuteSafe);
        registerOp(familyClass, factory, family, operation, variant, name, sources);
    }

    /**
     * Registers an instruction opcode. Called by instruction subclasses in their register() method.
     * Defaults to parallel-execute unsafe ({@code false}). Use the explicit overload to mark safe instructions.
     *
     * @param familyClass the instruction class (e.g., ArithmeticInstruction.class)
     * @param factory the factory for creating instruction instances (e.g., {@code ArithmeticInstruction::new})
     * @param family the family ID from {@link Family}
     * @param operation the operation number within the family
     * @param variant the variant from {@link Variant}
     * @param name the instruction mnemonic (e.g., "ADDR")
     * @param sources the operand sources for this instruction
     */
    protected static void registerOp(Class<? extends Instruction> familyClass, InstructionFactory factory,
                                     int family, int operation,
                                     int variant, String name, OperandSource... sources) {
        int opcodeId = OpcodeId.compute(family, operation, variant);
        int fullId = opcodeId | Config.TYPE_CODE;
        PARALLEL_EXECUTE_SAFE_MAP.putIfAbsent(fullId, false);
        registerFamily(familyClass, factory, java.util.Map.of(opcodeId, name), java.util.List.of(sources));
    }

    private static void registerFamily(Class<? extends Instruction> familyClass, InstructionFactory factory,
                                       Map<Integer, String> variants, List<OperandSource> sources) {
        List<InstructionArgumentType> argTypesForSignature = new ArrayList<>();
        for (OperandSource s : sources) {
            switch (s) {
                case REGISTER -> argTypesForSignature.add(InstructionArgumentType.REGISTER);
                case LOCATION_REGISTER -> argTypesForSignature.add(InstructionArgumentType.LOCATION_REGISTER);
                case IMMEDIATE -> argTypesForSignature.add(InstructionArgumentType.LITERAL);
                case VECTOR -> argTypesForSignature.add(InstructionArgumentType.VECTOR);
                case LABEL -> argTypesForSignature.add(InstructionArgumentType.LABEL);
                case STACK -> { /* STACK operands don't appear in signature */ }
            }
        }
        InstructionSignature signature = new InstructionSignature(argTypesForSignature);

        for (Map.Entry<Integer, String> entry : variants.entrySet()) {
            registerInstruction(familyClass, entry.getKey(), entry.getValue(), factory, signature);
            OPERAND_SOURCES.put(entry.getKey() | Config.TYPE_CODE, sources);
        }
    }

    private static void registerInstruction(Class<? extends Instruction> instructionClass, int id, String name,
                                            InstructionFactory factory, InstructionSignature signature) {
        String upperCaseName = name.toUpperCase();
        int fullId = id | Config.TYPE_CODE;
        REGISTERED_INSTRUCTIONS_BY_ID.put(fullId, instructionClass);
        NAME_TO_ID.put(upperCaseName, fullId);
        ID_TO_NAME.put(fullId, name);
        REGISTERED_PLANNERS_BY_ID.put(fullId, factory);
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
            for (RegisterBank bank : RegisterBank.values()) {
                if (bank.count > 0 && u.startsWith(bank.prefix)) {
                    int regNum = Integer.parseInt(u.substring(bank.prefixLength));
                    if (regNum < 0 || regNum >= bank.count) {
                        return Optional.empty();
                    }
                    return Optional.of(bank.base + regNum);
                }
            }
        } catch (NumberFormatException ignore) {
            // Falls through to empty Optional if, e.g., "%DR" has no number.
        }
        return Optional.empty();
    }

    // --- Static Getters for Runtime Information ---

    /**
     * Gets the length of the instruction in the given environment.
     * <p>
     * Instruction length depends on the environment's dimensionality because
     * VECTOR and LABEL operands have one component per dimension.
     *
     * @param env The environment (required for dimension-dependent length calculation).
     * @return The length of the instruction in memory slots.
     */
    /** Cached instruction length (-1 = not yet computed). */
    private int cachedLength = -1;

    public int getLength(Environment env) {
        if (cachedLength == -1) {
            cachedLength = getInstructionLengthById(this.fullOpcodeId, env);
        }
        return cachedLength;
    }

    /**
     * Gets the organism executing this instruction.
     * @return The organism.
     */
    public final Organism getOrganism() { return this.organism; }

    /**
     * Gets the name of this instruction.
     * @return The instruction's name.
     */
    public final String getName() {
        if (fullOpcodeId >= 0 && fullOpcodeId < REGISTRY_SIZE) {
            String name = NAMES_ARRAY[fullOpcodeId];
            if (name != null) return name;
        }
        return ID_TO_NAME.getOrDefault(this.fullOpcodeId, "UNKNOWN");
    }

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
    public static String getInstructionNameById(int id) {
        if (id >= 0 && id < REGISTRY_SIZE) {
            String name = NAMES_ARRAY[id];
            if (name != null) return name;
        }
        return ID_TO_NAME.getOrDefault(id, "UNKNOWN");
    }
    
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
     * Gets the length of an instruction by its ID in a given environment.
     * @param id The instruction ID.
     * @param env The environment.
     * @return The length of the instruction.
     */
    public static int getInstructionLengthById(int id, Environment env) {
        if (id >= 0 && id < REGISTRY_SIZE && INSTRUCTION_LENGTHS_BASE[id] > 0) {
            return INSTRUCTION_LENGTHS_BASE[id]
                 + INSTRUCTION_LENGTHS_DIMS_MULTIPLIER[id] * env.properties.getDimensions();
        }
        return 1;
    }

    /**
     * Gets the ID of an instruction by its name.
     * @param name The name of the instruction.
     * @return The instruction ID.
     */
    public static Integer getInstructionIdByName(String name) { return NAME_TO_ID.get(name.toUpperCase()); }

    /**
     * Gets the operand sources for an instruction by its opcode ID.
     * <p>
     * Returns the list of {@link OperandSource} values that define what code-stream
     * molecules an instruction reads. Used by mutation plugins to generate syntactically
     * correct instruction chains.
     *
     * @param opcodeId The instruction opcode ID (including TYPE_CODE bits).
     * @return Unmodifiable list of operand sources, or empty list if unknown.
     */
    public static List<OperandSource> getOperandSourcesById(int opcodeId) {
        List<OperandSource> sources = OPERAND_SOURCES.get(opcodeId);
        return sources != null ? Collections.unmodifiableList(sources) : List.of();
    }

    /**
     * Gets the factory for an instruction by its ID.
     * @param id The instruction ID.
     * @return The instruction factory, or null if unknown.
     */
    public static InstructionFactory getPlannerById(int id) {
        if (id >= 0 && id < REGISTRY_SIZE) {
            return PLANNERS_ARRAY[id];
        }
        return null;
    }

    /**
     * Gets the signature of an instruction by its ID.
     * @param id The instruction ID.
     * @return An Optional containing the instruction signature.
     */
    public static Optional<InstructionSignature> getSignatureById(int id) {
        if (id >= 0 && id < REGISTRY_SIZE) {
            return Optional.ofNullable(SIGNATURES_ARRAY[id]);
        }
        return Optional.ofNullable(SIGNATURES_BY_ID.get(id));
    }

    /**
     * Returns whether the instruction with the given opcode can safely execute in parallel.
     * <p>
     * Instructions that only modify organism-local state and perform read-only environment
     * access are parallel-safe. Instructions that write to shared environment structures
     * (PEEK/POKE/PPK, FORK, CMR) are not.
     * <p>
     * <b>Thread safety:</b> Safe for concurrent use. The backing array is read-only after {@link #init()}.
     *
     * @param fullOpcodeId The full opcode ID (including TYPE_CODE bits).
     * @return {@code true} if the instruction is safe for parallel execution, {@code false} otherwise.
     */
    public static boolean isParallelExecuteSafe(int fullOpcodeId) {
        return fullOpcodeId >= 0 && fullOpcodeId < PARALLEL_EXECUTE_SAFE.length
                && PARALLEL_EXECUTE_SAFE[fullOpcodeId];
    }

    // --- Conflict Resolution Logic ---

    /**
     * Whether the virtual machine processes this instruction in the current tick. Set for every
     * planned instruction of a living organism; a conflict loser keeps it, because the VM still
     * books the loss as a failed instruction without executing it.
     */
    protected boolean processedInTick = false;

    /**
     * The outcome of conflict resolution for an instruction.
     * <p>
     * {@link #LOST_PRIORITY} is assigned before execution: the instruction is still handed to the
     * virtual machine, which books it as a failed instruction without executing it and keeps the
     * instruction pointer for a retry. {@link #LOST_TARGET_OCCUPIED} is assigned during execution
     * when a write finds its target cell occupied. Thermodynamic policies charge only base costs
     * for any {@code LOST} status.
     */
    public enum ConflictResolutionStatus {
        NOT_APPLICABLE, WON_EXECUTION, LOST_TARGET_OCCUPIED, LOST_PRIORITY
    }
    protected ConflictResolutionStatus conflictStatus = ConflictResolutionStatus.NOT_APPLICABLE;
    
    /** Cached operands - resolved once, returned on subsequent calls (idempotent). */
    private List<Operand> cachedOperands = null;

    /** Argument slots as read from the environment, filled by {@link #resolveOperands(Environment)}. */
    private int[] rawArguments = null;

    /** Number of stack values that were peeked during resolveOperands() and need to be popped in commitStackReads(). */
    private int stackPeekCount = 0;


    /**
     * Checks whether the virtual machine processes this instruction in the current tick. A
     * conflict loser is processed too: it is booked as a failure instead of being executed.
     * @return true if processed in this tick, false otherwise.
     */
    public boolean isProcessedInTick() { return processedInTick; }

    /**
     * Sets whether the virtual machine processes this instruction in the current tick.
     * @param processedInTick true if processed, false otherwise.
     */
    public void setProcessedInTick(boolean processedInTick) { this.processedInTick = processedInTick; }

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