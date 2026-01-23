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
import static org.evochora.runtime.isa.Variant.*;

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
     */
    public static void init() {
        // Family 1: Arithmetic
        // Operation 0: ADD
        registerFamily(ArithmeticInstruction.class, Map.of(
            OpcodeId.compute(ARITHMETIC, 0, RR), "ADDR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(ArithmeticInstruction.class, Map.of(
            OpcodeId.compute(ARITHMETIC, 0, RI), "ADDI"
        ), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(ArithmeticInstruction.class, Map.of(
            OpcodeId.compute(ARITHMETIC, 0, SS), "ADDS"
        ), List.of(OperandSource.STACK, OperandSource.STACK));

        // Operation 1: SUB
        registerFamily(ArithmeticInstruction.class, Map.of(
            OpcodeId.compute(ARITHMETIC, 1, RR), "SUBR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(ArithmeticInstruction.class, Map.of(
            OpcodeId.compute(ARITHMETIC, 1, RI), "SUBI"
        ), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(ArithmeticInstruction.class, Map.of(
            OpcodeId.compute(ARITHMETIC, 1, SS), "SUBS"
        ), List.of(OperandSource.STACK, OperandSource.STACK));

        // Operation 2: MUL
        registerFamily(ArithmeticInstruction.class, Map.of(
            OpcodeId.compute(ARITHMETIC, 2, RR), "MULR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(ArithmeticInstruction.class, Map.of(
            OpcodeId.compute(ARITHMETIC, 2, RI), "MULI"
        ), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(ArithmeticInstruction.class, Map.of(
            OpcodeId.compute(ARITHMETIC, 2, SS), "MULS"
        ), List.of(OperandSource.STACK, OperandSource.STACK));

        // Operation 3: DIV
        registerFamily(ArithmeticInstruction.class, Map.of(
            OpcodeId.compute(ARITHMETIC, 3, RR), "DIVR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(ArithmeticInstruction.class, Map.of(
            OpcodeId.compute(ARITHMETIC, 3, RI), "DIVI"
        ), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(ArithmeticInstruction.class, Map.of(
            OpcodeId.compute(ARITHMETIC, 3, SS), "DIVS"
        ), List.of(OperandSource.STACK, OperandSource.STACK));

        // Operation 4: MOD
        registerFamily(ArithmeticInstruction.class, Map.of(
            OpcodeId.compute(ARITHMETIC, 4, RR), "MODR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(ArithmeticInstruction.class, Map.of(
            OpcodeId.compute(ARITHMETIC, 4, RI), "MODI"
        ), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(ArithmeticInstruction.class, Map.of(
            OpcodeId.compute(ARITHMETIC, 4, SS), "MODS"
        ), List.of(OperandSource.STACK, OperandSource.STACK));

        // Operation 12: DOT (dot product)
        registerFamily(ArithmeticInstruction.class, Map.of(
            OpcodeId.compute(ARITHMETIC, 12, RRR), "DOTR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(ArithmeticInstruction.class, Map.of(
            OpcodeId.compute(ARITHMETIC, 12, SS), "DOTS"
        ), List.of(OperandSource.STACK, OperandSource.STACK));

        // Operation 13: CRS (cross product)
        registerFamily(ArithmeticInstruction.class, Map.of(
            OpcodeId.compute(ARITHMETIC, 13, RRR), "CRSR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(ArithmeticInstruction.class, Map.of(
            OpcodeId.compute(ARITHMETIC, 13, SS), "CRSS"
        ), List.of(OperandSource.STACK, OperandSource.STACK));

        // Family 2: Bitwise
        // Operation 0: AND
        registerFamily(BitwiseInstruction.class, Map.of(
            OpcodeId.compute(BITWISE, 0, RR), "ANDR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(BitwiseInstruction.class, Map.of(
            OpcodeId.compute(BITWISE, 0, RI), "ANDI"
        ), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(BitwiseInstruction.class, Map.of(
            OpcodeId.compute(BITWISE, 0, SS), "ANDS"
        ), List.of(OperandSource.STACK, OperandSource.STACK));

        // Operation 1: OR
        registerFamily(BitwiseInstruction.class, Map.of(
            OpcodeId.compute(BITWISE, 1, RR), "ORR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(BitwiseInstruction.class, Map.of(
            OpcodeId.compute(BITWISE, 1, RI), "ORI"
        ), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(BitwiseInstruction.class, Map.of(
            OpcodeId.compute(BITWISE, 1, SS), "ORS"
        ), List.of(OperandSource.STACK, OperandSource.STACK));

        // Operation 2: XOR
        registerFamily(BitwiseInstruction.class, Map.of(
            OpcodeId.compute(BITWISE, 2, RR), "XORR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(BitwiseInstruction.class, Map.of(
            OpcodeId.compute(BITWISE, 2, RI), "XORI"
        ), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(BitwiseInstruction.class, Map.of(
            OpcodeId.compute(BITWISE, 2, SS), "XORS"
        ), List.of(OperandSource.STACK, OperandSource.STACK));

        // Operation 3: NAD (NAND)
        registerFamily(BitwiseInstruction.class, Map.of(
            OpcodeId.compute(BITWISE, 3, RR), "NADR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(BitwiseInstruction.class, Map.of(
            OpcodeId.compute(BITWISE, 3, RI), "NADI"
        ), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(BitwiseInstruction.class, Map.of(
            OpcodeId.compute(BITWISE, 3, SS), "NADS"
        ), List.of(OperandSource.STACK, OperandSource.STACK));

        // Operation 8: NOT
        registerFamily(BitwiseInstruction.class, Map.of(
            OpcodeId.compute(BITWISE, 8, R), "NOT"
        ), List.of(OperandSource.REGISTER));
        registerFamily(BitwiseInstruction.class, Map.of(
            OpcodeId.compute(BITWISE, 8, S), "NOTS"
        ), List.of(OperandSource.STACK));

        // Operation 9: SHL (Shift Left)
        registerFamily(BitwiseInstruction.class, Map.of(
            OpcodeId.compute(BITWISE, 9, RR), "SHLR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(BitwiseInstruction.class, Map.of(
            OpcodeId.compute(BITWISE, 9, RI), "SHLI"
        ), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(BitwiseInstruction.class, Map.of(
            OpcodeId.compute(BITWISE, 9, SS), "SHLS"
        ), List.of(OperandSource.STACK, OperandSource.STACK));

        // Operation 10: SHR (Shift Right)
        registerFamily(BitwiseInstruction.class, Map.of(
            OpcodeId.compute(BITWISE, 10, RR), "SHRR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(BitwiseInstruction.class, Map.of(
            OpcodeId.compute(BITWISE, 10, RI), "SHRI"
        ), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(BitwiseInstruction.class, Map.of(
            OpcodeId.compute(BITWISE, 10, SS), "SHRS"
        ), List.of(OperandSource.STACK, OperandSource.STACK));

        // Operation 11: ROT (Rotate)
        registerFamily(BitwiseInstruction.class, Map.of(
            OpcodeId.compute(BITWISE, 11, RR), "ROTR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(BitwiseInstruction.class, Map.of(
            OpcodeId.compute(BITWISE, 11, RI), "ROTI"
        ), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(BitwiseInstruction.class, Map.of(
            OpcodeId.compute(BITWISE, 11, SS), "ROTS"
        ), List.of(OperandSource.STACK, OperandSource.STACK));

        // Operation 12: PCN (Population Count)
        registerFamily(BitwiseInstruction.class, Map.of(
            OpcodeId.compute(BITWISE, 12, RR), "PCNR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(BitwiseInstruction.class, Map.of(
            OpcodeId.compute(BITWISE, 12, S), "PCNS"
        ), List.of(OperandSource.STACK));

        // Operation 13: BSN (Bit Scan N-th)
        registerFamily(BitwiseInstruction.class, Map.of(
            OpcodeId.compute(BITWISE, 13, RRR), "BSNR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(BitwiseInstruction.class, Map.of(
            OpcodeId.compute(BITWISE, 13, RRI), "BSNI"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(BitwiseInstruction.class, Map.of(
            OpcodeId.compute(BITWISE, 13, SS), "BSNS"
        ), List.of(OperandSource.STACK, OperandSource.STACK));

        // Family 3: Data
        // Operation 0: SET (copy value to register)
        registerFamily(DataInstruction.class, Map.of(
            OpcodeId.compute(DATA, 0, RR), "SETR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(DataInstruction.class, Map.of(
            OpcodeId.compute(DATA, 0, RI), "SETI"
        ), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(DataInstruction.class, Map.of(
            OpcodeId.compute(DATA, 0, RV), "SETV"
        ), List.of(OperandSource.REGISTER, OperandSource.VECTOR));

        // Operation 1: PUSH (push value onto stack)
        registerFamily(DataInstruction.class, Map.of(
            OpcodeId.compute(DATA, 1, R), "PUSH"
        ), List.of(OperandSource.REGISTER));
        registerFamily(DataInstruction.class, Map.of(
            OpcodeId.compute(DATA, 1, I), "PUSI"
        ), List.of(OperandSource.IMMEDIATE));
        registerFamily(DataInstruction.class, Map.of(
            OpcodeId.compute(DATA, 1, V), "PUSV"
        ), List.of(OperandSource.VECTOR));

        // Operation 2: POP (pop value from stack)
        registerFamily(DataInstruction.class, Map.of(
            OpcodeId.compute(DATA, 2, R), "POP"
        ), List.of(OperandSource.REGISTER));

        // Operation 3: DUP (duplicate top of stack)
        registerFamily(StackInstruction.class, Map.of(
            OpcodeId.compute(DATA, 3, NONE), "DUP"
        ), List.of());

        // Operation 4: SWAP (swap top two stack values)
        registerFamily(StackInstruction.class, Map.of(
            OpcodeId.compute(DATA, 4, NONE), "SWAP"
        ), List.of());

        // Operation 5: DROP (discard top of stack)
        registerFamily(StackInstruction.class, Map.of(
            OpcodeId.compute(DATA, 5, NONE), "DROP"
        ), List.of());

        // Operation 6: ROT (stack rotate)
        registerFamily(StackInstruction.class, Map.of(
            OpcodeId.compute(DATA, 6, NONE), "ROT"
        ), List.of());

        // Family 4: Conditional
        // Operation 0: IF/EQ (If Equal)
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 0, RR), "IFR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 0, RI), "IFI"
        ), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 0, SS), "IFS"
        ), List.of(OperandSource.STACK, OperandSource.STACK));

        // Operation 1: NE (Not Equal)
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 1, RR), "INR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 1, RI), "INI"
        ), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 1, SS), "INS"
        ), List.of(OperandSource.STACK, OperandSource.STACK));

        // Operation 2: LT (Less Than)
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 2, RR), "LTR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 2, RI), "LTI"
        ), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 2, SS), "LTS"
        ), List.of(OperandSource.STACK, OperandSource.STACK));

        // Operation 3: GT (Greater Than)
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 3, RR), "GTR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 3, RI), "GTI"
        ), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 3, SS), "GTS"
        ), List.of(OperandSource.STACK, OperandSource.STACK));

        // Operation 4: LE (Less Than or Equal)
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 4, RR), "LETR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 4, RI), "LETI"
        ), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 4, SS), "LETS"
        ), List.of(OperandSource.STACK, OperandSource.STACK));

        // Operation 5: GE (Greater Than or Equal)
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 5, RR), "GETR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 5, RI), "GETI"
        ), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 5, SS), "GETS"
        ), List.of(OperandSource.STACK, OperandSource.STACK));

        // Operation 6: IFT (If True / non-zero)
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 6, RR), "IFTR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 6, RI), "IFTI"
        ), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 6, SS), "IFTS"
        ), List.of(OperandSource.STACK, OperandSource.STACK));

        // Operation 7: INT (If Not True / zero)
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 7, RR), "INTR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 7, RI), "INTI"
        ), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 7, SS), "INTS"
        ), List.of(OperandSource.STACK, OperandSource.STACK));

        // Operation 8: IFM (If Mine - ownership check)
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 8, R), "IFMR"
        ), List.of(OperandSource.REGISTER));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 8, V), "IFMI"  // Note: uses VECTOR operand despite "I" suffix
        ), List.of(OperandSource.VECTOR));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 8, S), "IFMS"
        ), List.of(OperandSource.STACK));

        // Operation 9: INM (If Not Mine - ownership check)
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 9, R), "INMR"
        ), List.of(OperandSource.REGISTER));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 9, V), "INMI"  // Note: uses VECTOR operand despite "I" suffix
        ), List.of(OperandSource.VECTOR));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 9, S), "INMS"
        ), List.of(OperandSource.STACK));

        // Operation 10: IFP (If Passable)
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 10, R), "IFPR"
        ), List.of(OperandSource.REGISTER));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 10, V), "IFPI"  // Note: uses VECTOR operand despite "I" suffix
        ), List.of(OperandSource.VECTOR));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 10, S), "IFPS"
        ), List.of(OperandSource.STACK));

        // Operation 11: INP (If Not Passable)
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 11, R), "INPR"
        ), List.of(OperandSource.REGISTER));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 11, V), "INPI"  // Note: uses VECTOR operand despite "I" suffix
        ), List.of(OperandSource.VECTOR));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 11, S), "INPS"
        ), List.of(OperandSource.STACK));

        // Operation 12: IFF (If Foreign ownership)
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 12, R), "IFFR"
        ), List.of(OperandSource.REGISTER));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 12, V), "IFFI"  // Note: uses VECTOR operand despite "I" suffix
        ), List.of(OperandSource.VECTOR));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 12, S), "IFFS"
        ), List.of(OperandSource.STACK));

        // Operation 13: INF (If Not Foreign ownership)
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 13, R), "INFR"
        ), List.of(OperandSource.REGISTER));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 13, V), "INFI"  // Note: uses VECTOR operand despite "I" suffix
        ), List.of(OperandSource.VECTOR));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 13, S), "INFS"
        ), List.of(OperandSource.STACK));

        // Operation 14: IFV (If Vacant ownership)
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 14, R), "IFVR"
        ), List.of(OperandSource.REGISTER));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 14, V), "IFVI"  // Note: uses VECTOR operand despite "I" suffix
        ), List.of(OperandSource.VECTOR));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 14, S), "IFVS"
        ), List.of(OperandSource.STACK));

        // Operation 15: INV (If Not Vacant ownership)
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 15, R), "INVR"
        ), List.of(OperandSource.REGISTER));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 15, V), "INVI"  // Note: uses VECTOR operand despite "I" suffix
        ), List.of(OperandSource.VECTOR));
        registerFamily(ConditionalInstruction.class, Map.of(
            OpcodeId.compute(CONDITIONAL, 15, S), "INVS"
        ), List.of(OperandSource.STACK));

        // Family 5: Control Flow
        // Operation 0: JMP (Jump)
        registerFamily(ControlFlowInstruction.class, Map.of(
            OpcodeId.compute(CONTROL, 0, R), "JMPR"
        ), List.of(OperandSource.REGISTER));
        registerFamily(ControlFlowInstruction.class, Map.of(
            OpcodeId.compute(CONTROL, 0, S), "JMPS"
        ), List.of(OperandSource.STACK));
        registerFamily(ControlFlowInstruction.class, Map.of(
            OpcodeId.compute(CONTROL, 0, L), "JMPI"
        ), List.of(OperandSource.LABEL));

        // Operation 1: CALL (Call subroutine)
        registerFamily(ControlFlowInstruction.class, Map.of(
            OpcodeId.compute(CONTROL, 1, L), "CALL"
        ), List.of(OperandSource.LABEL));

        // Operation 2: RET (Return from subroutine)
        registerFamily(ControlFlowInstruction.class, Map.of(
            OpcodeId.compute(CONTROL, 2, NONE), "RET"
        ), List.of());

        // Family 6: Environment
        // Operation 0: PEEK (read value from environment cell)
        registerFamily(EnvironmentInteractionInstruction.class, Map.of(
            OpcodeId.compute(ENVIRONMENT, 0, RR), "PEEK"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(EnvironmentInteractionInstruction.class, Map.of(
            OpcodeId.compute(ENVIRONMENT, 0, RV), "PEKI"
        ), List.of(OperandSource.REGISTER, OperandSource.VECTOR));
        registerFamily(EnvironmentInteractionInstruction.class, Map.of(
            OpcodeId.compute(ENVIRONMENT, 0, S), "PEKS"
        ), List.of(OperandSource.STACK));

        // Operation 1: POKE (write value to environment cell)
        registerFamily(EnvironmentInteractionInstruction.class, Map.of(
            OpcodeId.compute(ENVIRONMENT, 1, RR), "POKE"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(EnvironmentInteractionInstruction.class, Map.of(
            OpcodeId.compute(ENVIRONMENT, 1, RV), "POKI"
        ), List.of(OperandSource.REGISTER, OperandSource.VECTOR));
        registerFamily(EnvironmentInteractionInstruction.class, Map.of(
            OpcodeId.compute(ENVIRONMENT, 1, SS), "POKS"
        ), List.of(OperandSource.STACK, OperandSource.STACK));

        // Operation 2: PPK (combined PEEK+POKE)
        registerFamily(EnvironmentInteractionInstruction.class, Map.of(
            OpcodeId.compute(ENVIRONMENT, 2, RR), "PPKR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(EnvironmentInteractionInstruction.class, Map.of(
            OpcodeId.compute(ENVIRONMENT, 2, RV), "PPKI"
        ), List.of(OperandSource.REGISTER, OperandSource.VECTOR));
        registerFamily(EnvironmentInteractionInstruction.class, Map.of(
            OpcodeId.compute(ENVIRONMENT, 2, SS), "PPKS"
        ), List.of(OperandSource.STACK, OperandSource.STACK));

        // Family 7: State
        // Operation 0: SCAN (scan environment in direction)
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 0, RR), "SCAN"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 0, RV), "SCNI"
        ), List.of(OperandSource.REGISTER, OperandSource.VECTOR));
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 0, S), "SCNS"
        ), List.of(OperandSource.STACK));

        // Operation 1: SEEK (set active data pointer direction)
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 1, R), "SEEK"
        ), List.of(OperandSource.REGISTER));
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 1, V), "SEKI"
        ), List.of(OperandSource.VECTOR));
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 1, S), "SEKS"
        ), List.of(OperandSource.STACK));

        // Operation 2: TURN (turn/rotate direction)
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 2, R), "TURN"
        ), List.of(OperandSource.REGISTER));
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 2, V), "TRNI"
        ), List.of(OperandSource.VECTOR));
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 2, S), "TRNS"
        ), List.of(OperandSource.STACK));

        // Operation 3: SYNC (synchronize/wait)
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 3, NONE), "SYNC"
        ), List.of());

        // Operation 4: NRG (get energy)
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 4, R), "NRG"
        ), List.of(OperandSource.REGISTER));
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 4, NONE), "NRGS"
        ), List.of());

        // Operation 5: NTR (get entropy)
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 5, R), "NTR"
        ), List.of(OperandSource.REGISTER));
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 5, NONE), "NTRS"
        ), List.of());

        // Operation 6: DIFF (get difficulty/thermodynamic gradient)
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 6, R), "DIFF"
        ), List.of(OperandSource.REGISTER));
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 6, NONE), "DIFS"
        ), List.of());

        // Operation 7: POS (get position)
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 7, R), "POS"
        ), List.of(OperandSource.REGISTER));
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 7, NONE), "POSS"
        ), List.of());

        // Operation 8: RAND (random number)
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 8, R), "RAND"
        ), List.of(OperandSource.REGISTER));
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 8, S), "RNDS"
        ), List.of(OperandSource.STACK));

        // Operation 9: FORK (replicate organism)
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 9, RRR), "FORK"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 9, VIV), "FRKI"
        ), List.of(OperandSource.VECTOR, OperandSource.IMMEDIATE, OperandSource.VECTOR));
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 9, SSS), "FRKS"
        ), List.of(OperandSource.STACK, OperandSource.STACK, OperandSource.STACK));

        // Operation 10: ADP (active data pointer selection)
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 10, R), "ADPR"
        ), List.of(OperandSource.REGISTER));
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 10, I), "ADPI"
        ), List.of(OperandSource.IMMEDIATE));
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 10, S), "ADPS"
        ), List.of(OperandSource.STACK));

        // Operation 11: SPN (scan passable neighbors)
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 11, R), "SPNR"
        ), List.of(OperandSource.REGISTER));
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 11, NONE), "SPNS"
        ), List.of());

        // Operation 12: SNT (scan neighbors by type)
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 12, RR), "SNTR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 12, RI), "SNTI"
        ), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 12, S), "SNTS"
        ), List.of(OperandSource.STACK));

        // Operation 13: RBI (random bit from mask)
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 13, RR), "RBIR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 13, RI), "RBII"
        ), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 13, S), "RBIS"
        ), List.of(OperandSource.STACK));

        // Operation 14: GDV (get DV value)
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 14, R), "GDVR"
        ), List.of(OperandSource.REGISTER));
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 14, NONE), "GDVS"
        ), List.of());

        // Operation 15: SMR (set molecule marker register)
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 15, R), "SMR"
        ), List.of(OperandSource.REGISTER));
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 15, I), "SMRI"
        ), List.of(OperandSource.IMMEDIATE));
        registerFamily(StateInstruction.class, Map.of(
            OpcodeId.compute(STATE, 15, S), "SMRS"
        ), List.of(OperandSource.STACK));

        // Family 0: Special
        registerFamily(NopInstruction.class, Map.of(
            OpcodeId.compute(SPECIAL, 0, NONE), "NOP"
        ), List.of());

        // Family 8: Location
        // Operation 0: DPL (Duplicate location)
        registerFamily(LocationInstruction.class, Map.of(
            OpcodeId.compute(LOCATION, 0, S), "DPLS"
        ), List.of());
        registerFamily(LocationInstruction.class, Map.of(
            OpcodeId.compute(LOCATION, 0, L), "DPLR"
        ), List.of(OperandSource.LOCATION_REGISTER));

        // Operation 1: SKL (Skip location)
        registerFamily(LocationInstruction.class, Map.of(
            OpcodeId.compute(LOCATION, 1, S), "SKLS"
        ), List.of());
        registerFamily(LocationInstruction.class, Map.of(
            OpcodeId.compute(LOCATION, 1, L), "SKLR"
        ), List.of(OperandSource.LOCATION_REGISTER));

        // Operation 2: LRD (Location register displacement)
        registerFamily(LocationInstruction.class, Map.of(
            OpcodeId.compute(LOCATION, 2, L), "LRDS"
        ), List.of(OperandSource.LOCATION_REGISTER));
        registerFamily(LocationInstruction.class, Map.of(
            OpcodeId.compute(LOCATION, 2, RL), "LRDR"
        ), List.of(OperandSource.REGISTER, OperandSource.LOCATION_REGISTER));

        // Operation 3: LSD (Location stack displacement)
        registerFamily(LocationInstruction.class, Map.of(
            OpcodeId.compute(LOCATION, 3, NONE), "LSDS"
        ), List.of());
        registerFamily(LocationInstruction.class, Map.of(
            OpcodeId.compute(LOCATION, 3, R), "LSDR"
        ), List.of(OperandSource.REGISTER));

        // Operation 4: PUSL (Push location)
        registerFamily(LocationInstruction.class, Map.of(
            OpcodeId.compute(LOCATION, 4, L), "PUSL"
        ), List.of(OperandSource.LOCATION_REGISTER));

        // Operation 5: POPL (Pop location)
        registerFamily(LocationInstruction.class, Map.of(
            OpcodeId.compute(LOCATION, 5, L), "POPL"
        ), List.of(OperandSource.LOCATION_REGISTER));

        // Operation 6: DUPL (Duplicate top of location stack)
        registerFamily(LocationInstruction.class, Map.of(
            OpcodeId.compute(LOCATION, 6, NONE), "DUPL"
        ), List.of());

        // Operation 7: SWPL (Swap location stack)
        registerFamily(LocationInstruction.class, Map.of(
            OpcodeId.compute(LOCATION, 7, NONE), "SWPL"
        ), List.of());

        // Operation 8: DRPL (Drop from location stack)
        registerFamily(LocationInstruction.class, Map.of(
            OpcodeId.compute(LOCATION, 8, NONE), "DRPL"
        ), List.of());

        // Operation 9: ROTL (Rotate location stack)
        registerFamily(LocationInstruction.class, Map.of(
            OpcodeId.compute(LOCATION, 9, NONE), "ROTL"
        ), List.of());

        // Operation 10: CRL (Clear location register)
        registerFamily(LocationInstruction.class, Map.of(
            OpcodeId.compute(LOCATION, 10, L), "CRLR"
        ), List.of(OperandSource.LOCATION_REGISTER));

        // Operation 11: LRL (Load location register)
        registerFamily(LocationInstruction.class, Map.of(
            OpcodeId.compute(LOCATION, 11, LL), "LRLR"
        ), List.of(OperandSource.LOCATION_REGISTER, OperandSource.LOCATION_REGISTER));

        // Vector Manipulation Instruction Family (Family 9)

        // Operation 0: VGT (Vector get)
        registerFamily(VectorInstruction.class, Map.of(
            OpcodeId.compute(VECTOR, 0, RRR), "VGTR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(VectorInstruction.class, Map.of(
            OpcodeId.compute(VECTOR, 0, RRI), "VGTI"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(VectorInstruction.class, Map.of(
            OpcodeId.compute(VECTOR, 0, SS), "VGTS"
        ), List.of(OperandSource.STACK, OperandSource.STACK)); // index, vector

        // Operation 1: VST (Vector set)
        registerFamily(VectorInstruction.class, Map.of(
            OpcodeId.compute(VECTOR, 1, RRR), "VSTR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(VectorInstruction.class, Map.of(
            OpcodeId.compute(VECTOR, 1, RII), "VSTI"
        ), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE, OperandSource.IMMEDIATE));
        registerFamily(VectorInstruction.class, Map.of(
            OpcodeId.compute(VECTOR, 1, SSS), "VSTS"
        ), List.of(OperandSource.STACK, OperandSource.STACK, OperandSource.STACK)); // value, index, vector

        // Operation 2: VBL (Vector build)
        registerFamily(VectorInstruction.class, Map.of(
            OpcodeId.compute(VECTOR, 2, R), "VBLD"
        ), List.of(OperandSource.REGISTER));
        registerFamily(VectorInstruction.class, Map.of(
            OpcodeId.compute(VECTOR, 2, NONE), "VBLS"
        ), List.of());

        // Operation 3: B2V (Bytes to vector)
        registerFamily(VectorInstruction.class, Map.of(
            OpcodeId.compute(VECTOR, 3, RR), "B2VR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(VectorInstruction.class, Map.of(
            OpcodeId.compute(VECTOR, 3, RI), "B2VI"
        ), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(VectorInstruction.class, Map.of(
            OpcodeId.compute(VECTOR, 3, S), "B2VS"
        ), List.of(OperandSource.STACK)); // mask

        // Operation 4: V2B (Vector to bytes)
        registerFamily(VectorInstruction.class, Map.of(
            OpcodeId.compute(VECTOR, 4, RR), "V2BR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(VectorInstruction.class, Map.of(
            OpcodeId.compute(VECTOR, 4, RV), "V2BI"
        ), List.of(OperandSource.REGISTER, OperandSource.VECTOR));
        registerFamily(VectorInstruction.class, Map.of(
            OpcodeId.compute(VECTOR, 4, S), "V2BS"
        ), List.of(OperandSource.STACK)); // vector

        // Operation 5: RTR (Retarget / Rotate Right by 90 degrees in plane of two axes)
        registerFamily(VectorInstruction.class, Map.of(
            OpcodeId.compute(VECTOR, 5, RRR), "RTRR"
        ), List.of(OperandSource.REGISTER, OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(VectorInstruction.class, Map.of(
            OpcodeId.compute(VECTOR, 5, RII), "RTRI"
        ), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE, OperandSource.IMMEDIATE));
        registerFamily(VectorInstruction.class, Map.of(
            OpcodeId.compute(VECTOR, 5, SSS), "RTRS"
        ), List.of(OperandSource.STACK, OperandSource.STACK, OperandSource.STACK)); // axis2, axis1, vector
    }

    private static final int DEFAULT_VECTOR_DIMS = 2;

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