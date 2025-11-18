// in: src/main/java/org/evochora/runtime/isa/Instruction.java

package org.evochora.runtime.isa;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.instructions.*;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;

import java.lang.reflect.Constructor;
import java.util.*;
import java.util.function.BiFunction;

/**
 * The abstract base class for all instructions in the Evochora VM.
 * This class is now free from legacy compiler dependencies and focuses
 * exclusively on runtime logic.
 */
public abstract class Instruction {

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
     * @param environment The environment in which the instruction is executed.
     * @return A list of resolved operands.
     */
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
        return resolved;
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
        // Arithmetic-Family
        registerFamily(ArithmeticInstruction.class, Map.of(4, "ADDR", 6, "SUBR", 40, "MULR", 42, "DIVR", 44, "MODR"), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(ArithmeticInstruction.class, Map.of(30, "ADDI", 31, "SUBI", 41, "MULI", 43, "DIVI", 45, "MODI"), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(ArithmeticInstruction.class, Map.of(70, "ADDS", 71, "SUBS", 72, "MULS", 73, "DIVS", 74, "MODS"), List.of(OperandSource.STACK, OperandSource.STACK));

        // Bitwise-Family
        registerFamily(BitwiseInstruction.class, Map.of(5, "NADR", 46, "ANDR", 48, "ORR", 50, "XORR"), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(BitwiseInstruction.class, Map.of(32, "NADI", 47, "ANDI", 49, "ORI", 51, "XORI", 53, "SHLI", 54, "SHRI"), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(BitwiseInstruction.class, Map.of(78, "NADS", 75, "ANDS", 76, "ORS", 77, "XORS", 80, "SHLS", 81, "SHRS"), List.of(OperandSource.STACK, OperandSource.STACK));
        // New register shift variants
        registerFamily(BitwiseInstruction.class, Map.of(103, "SHLR", 104, "SHRR"), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(BitwiseInstruction.class, Map.of(52, "NOT"), List.of(OperandSource.REGISTER));
        registerFamily(BitwiseInstruction.class, Map.of(79, "NOTS"), List.of(OperandSource.STACK));

        // New: Rotate (ROT), Population Count (PCN), Bit Scan N-th (BSN)
        // Allocate new IDs beyond current max (>=134)
        registerFamily(BitwiseInstruction.class, Map.of(135, "ROTR"), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(BitwiseInstruction.class, Map.of(136, "ROTI"), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(BitwiseInstruction.class, Map.of(137, "ROTS"), List.of(OperandSource.STACK, OperandSource.STACK));

        registerFamily(BitwiseInstruction.class, Map.of(138, "PCNR"), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(BitwiseInstruction.class, Map.of(139, "PCNS"), List.of(OperandSource.STACK));

        registerFamily(BitwiseInstruction.class, Map.of(140, "BSNR"), List.of(OperandSource.REGISTER, OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(BitwiseInstruction.class, Map.of(141, "BSNI"), List.of(OperandSource.REGISTER, OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(BitwiseInstruction.class, Map.of(142, "BSNS"), List.of(OperandSource.STACK, OperandSource.STACK));

        // Data-Family
        registerFamily(DataInstruction.class, Map.of(1, "SETI"), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(DataInstruction.class, Map.of(2, "SETR"), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(DataInstruction.class, Map.of(3, "SETV"), List.of(OperandSource.REGISTER, OperandSource.VECTOR));
        registerFamily(DataInstruction.class, Map.of(22, "PUSH"), List.of(OperandSource.REGISTER));
        registerFamily(DataInstruction.class, Map.of(23, "POP"), List.of(OperandSource.REGISTER));
        registerFamily(DataInstruction.class, Map.of(58, "PUSI"), List.of(OperandSource.IMMEDIATE));
        registerFamily(DataInstruction.class, Map.of(178, "PUSV"), List.of(OperandSource.VECTOR));

        // Stack-Family
        registerFamily(StackInstruction.class, Map.of(60, "DUP", 61, "SWAP", 62, "DROP", 63, "ROT"), List.of());

        // Conditional-Family
        registerFamily(ConditionalInstruction.class, Map.of(7, "IFR", 8, "LTR", 9, "GTR", 33, "IFTR"), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(ConditionalInstruction.class, Map.of(24, "IFI", 25, "LTI", 26, "GTI", 29, "IFTI"), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(ConditionalInstruction.class, Map.of(85, "IFS", 86, "GTS", 87, "LTS", 88, "IFTS"), List.of(OperandSource.STACK, OperandSource.STACK));
        registerFamily(ConditionalInstruction.class, Map.of(93, "IFMR"), List.of(OperandSource.REGISTER));
        registerFamily(ConditionalInstruction.class, Map.of(94, "IFMI"), List.of(OperandSource.VECTOR));
        registerFamily(ConditionalInstruction.class, Map.of(95, "IFMS"), List.of(OperandSource.STACK));
        registerFamily(ConditionalInstruction.class, Map.of(182, "IFPR"), List.of(OperandSource.REGISTER));
        registerFamily(ConditionalInstruction.class, Map.of(183, "IFPI"), List.of(OperandSource.VECTOR));
        registerFamily(ConditionalInstruction.class, Map.of(184, "IFPS"), List.of(OperandSource.STACK));

        // Negated Conditional-Family
        registerFamily(ConditionalInstruction.class, Map.of(163, "INR", 164, "GETR", 165, "LETR", 166, "INTR"), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(ConditionalInstruction.class, Map.of(167, "GETI", 168, "LETI", 169, "INTI", 170, "INI"), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(ConditionalInstruction.class, Map.of(171, "INS", 172, "GETS", 173, "LETS", 174, "INTS"), List.of(OperandSource.STACK, OperandSource.STACK));
        registerFamily(ConditionalInstruction.class, Map.of(175, "INMR"), List.of(OperandSource.REGISTER));
        registerFamily(ConditionalInstruction.class, Map.of(176, "INMI"), List.of(OperandSource.VECTOR));
        registerFamily(ConditionalInstruction.class, Map.of(177, "INMS"), List.of(OperandSource.STACK));
        registerFamily(ConditionalInstruction.class, Map.of(185, "INPR"), List.of(OperandSource.REGISTER));
        registerFamily(ConditionalInstruction.class, Map.of(186, "INPI"), List.of(OperandSource.VECTOR));
        registerFamily(ConditionalInstruction.class, Map.of(187, "INPS"), List.of(OperandSource.STACK));

        // ControlFlow-Family
        registerFamily(ControlFlowInstruction.class, Map.of(20, "JMPI", 34, "CALL"), List.of(OperandSource.LABEL));
        registerFamily(ControlFlowInstruction.class, Map.of(10, "JMPR"), List.of(OperandSource.REGISTER));
        registerFamily(ControlFlowInstruction.class, Map.of(89, "JMPS"), List.of(OperandSource.STACK));
        registerFamily(ControlFlowInstruction.class, Map.of(35, "RET"), List.of());

        // WorldInteraction (POKE & PEEK)
        registerFamily(EnvironmentInteractionInstruction.class, Map.of(15, "POKE", 14, "PEEK"), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(EnvironmentInteractionInstruction.class, Map.of(57, "POKI", 56, "PEKI"), List.of(OperandSource.REGISTER, OperandSource.VECTOR));
        registerFamily(EnvironmentInteractionInstruction.class, Map.of(91, "POKS"), List.of(OperandSource.STACK, OperandSource.STACK));
        registerFamily(EnvironmentInteractionInstruction.class, Map.of(90, "PEKS"), List.of(OperandSource.STACK));
        // Combined PEEK+POKE instructions
        registerFamily(EnvironmentInteractionInstruction.class, Map.of(179, "PPKR"), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(EnvironmentInteractionInstruction.class, Map.of(180, "PPKI"), List.of(OperandSource.REGISTER, OperandSource.VECTOR));
        registerFamily(EnvironmentInteractionInstruction.class, Map.of(181, "PPKS"), List.of(OperandSource.STACK, OperandSource.STACK));

        // State (SCAN, SEEK & Rest)
        registerFamily(StateInstruction.class, Map.of(16, "SCAN"), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(StateInstruction.class, Map.of(82, "SCNI"), List.of(OperandSource.REGISTER, OperandSource.VECTOR));
        registerFamily(StateInstruction.class, Map.of(83, "SCNS"), List.of(OperandSource.STACK));
        // New: SPNP (Scan Passable Neighbors): SPNR/SPNS
        registerFamily(StateInstruction.class, Map.of(152, "SPNR"), List.of(OperandSource.REGISTER));
        registerFamily(StateInstruction.class, Map.of(153, "SPNS"), List.of());
        // New: SNT* (Scan Neighbors by Type): SNTR/SNTI/SNTS
        registerFamily(StateInstruction.class, Map.of(154, "SNTR"), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(StateInstruction.class, Map.of(155, "SNTI"), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(StateInstruction.class, Map.of(156, "SNTS"), List.of(OperandSource.STACK));
        registerFamily(StateInstruction.class, Map.of(12, "SEEK"), List.of(OperandSource.REGISTER));
        registerFamily(StateInstruction.class, Map.of(59, "SEKI"), List.of(OperandSource.VECTOR));
        registerFamily(StateInstruction.class, Map.of(84, "SEKS"), List.of(OperandSource.STACK));
        registerFamily(StateInstruction.class, Map.of(11, "TURN", 17, "NRG", 19, "DIFF", 21, "POS", 55, "RAND"), List.of(OperandSource.REGISTER));
        registerFamily(StateInstruction.class, Map.of(18, "FORK"), List.of(OperandSource.REGISTER, OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(StateInstruction.class, Map.of(13, "SYNC", 92, "NRGS"), List.of());
        // New: TRNI, TRNS, POSS, DIFS (allocate new IDs > current max 95?)
        registerFamily(StateInstruction.class, Map.of(96, "TRNI"), List.of(OperandSource.VECTOR));
        registerFamily(StateInstruction.class, Map.of(97, "TRNS"), List.of());
        registerFamily(StateInstruction.class, Map.of(98, "POSS"), List.of());
        registerFamily(StateInstruction.class, Map.of(99, "DIFS"), List.of());
        // New: RNDS (pops from stack in handler)
        registerFamily(StateInstruction.class, Map.of(105, "RNDS"), List.of());
        // New: Active DP selection ADPR/ADPI/ADPS
        registerFamily(StateInstruction.class, Map.of(100, "ADPR"), List.of(OperandSource.REGISTER));
        registerFamily(StateInstruction.class, Map.of(101, "ADPI"), List.of(OperandSource.IMMEDIATE));
        registerFamily(StateInstruction.class, Map.of(102, "ADPS"), List.of());
        // New: FRKI and FRKS
        registerFamily(StateInstruction.class, Map.of(106, "FRKI"), List.of(OperandSource.VECTOR, OperandSource.IMMEDIATE, OperandSource.VECTOR));
        registerFamily(StateInstruction.class, Map.of(107, "FRKS"), List.of());

        // NOP
        registerFamily(NopInstruction.class, Map.of(0, "NOP"), List.of());

        // Arithmetic extensions: DOT and CRS
        registerFamily(ArithmeticInstruction.class, Map.of(108, "DOTR", 109, "CRSR"), List.of(OperandSource.REGISTER, OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(ArithmeticInstruction.class, Map.of(110, "DOTS", 111, "CRSS"), List.of(OperandSource.STACK, OperandSource.STACK));

        // Location instruction family registrations
        registerFamily(LocationInstruction.class, Map.of(112, "DUPL", 113, "SWPL", 114, "DRPL", 115, "ROTL", 116, "DPLS", 117, "SKLS", 122, "LSDS"), List.of());
        registerFamily(LocationInstruction.class, Map.of(118, "DPLR", 120, "SKLR", 121, "PUSL", 123, "LRDS", 125, "POPL", 191, "CRLR"), List.of(OperandSource.LOCATION_REGISTER));
        registerFamily(LocationInstruction.class, Map.of(190, "LRLR"), List.of(OperandSource.LOCATION_REGISTER, OperandSource.LOCATION_REGISTER));
        registerFamily(LocationInstruction.class, Map.of(124, "LRDR"), List.of(OperandSource.REGISTER, OperandSource.LOCATION_REGISTER));
        registerFamily(LocationInstruction.class, Map.of(126, "LSDR"), List.of(OperandSource.REGISTER));

        // Vector Manipulation Instruction Family
        registerFamily(VectorInstruction.class, Map.of(127, "VGTR"), List.of(OperandSource.REGISTER, OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(VectorInstruction.class, Map.of(128, "VGTI"), List.of(OperandSource.REGISTER, OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(VectorInstruction.class, Map.of(129, "VGTS"), List.of()); // Operands from stack
        registerFamily(VectorInstruction.class, Map.of(130, "VSTR"), List.of(OperandSource.REGISTER, OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(VectorInstruction.class, Map.of(131, "VSTI"), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE, OperandSource.IMMEDIATE));
        registerFamily(VectorInstruction.class, Map.of(132, "VSTS"), List.of()); // Operands from stack
        registerFamily(VectorInstruction.class, Map.of(133, "VBLD"), List.of(OperandSource.REGISTER));
        registerFamily(VectorInstruction.class, Map.of(134, "VBLS"), List.of());

        // New: B2V family (bit to vector)
        registerFamily(VectorInstruction.class, Map.of(146, "B2VR"), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(VectorInstruction.class, Map.of(147, "B2VI"), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(VectorInstruction.class, Map.of(148, "B2VS"), List.of());

        // New: V2B family (vector to bit)
        registerFamily(VectorInstruction.class, Map.of(157, "V2BR"), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(VectorInstruction.class, Map.of(158, "V2BI"), List.of(OperandSource.REGISTER, OperandSource.VECTOR));
        registerFamily(VectorInstruction.class, Map.of(159, "V2BS"), List.of());
        // New: RTR* family (Rotate Right by 90° in plane of two axes)
        registerFamily(VectorInstruction.class, Map.of(160, "RTRR"), List.of(OperandSource.REGISTER, OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(VectorInstruction.class, Map.of(161, "RTRI"), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE, OperandSource.IMMEDIATE));
        registerFamily(VectorInstruction.class, Map.of(162, "RTRS"), List.of());
        // New: RBIT family (random bit from mask)
        registerFamily(StateInstruction.class, Map.of(149, "RBIR"), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
        registerFamily(StateInstruction.class, Map.of(150, "RBII"), List.of(OperandSource.REGISTER, OperandSource.IMMEDIATE));
        registerFamily(StateInstruction.class, Map.of(151, "RBIS"), List.of());
        // New: GDVR and GDVS (get DV value)
        registerFamily(StateInstruction.class, Map.of(188, "GDVR"), List.of(OperandSource.REGISTER));
        registerFamily(StateInstruction.class, Map.of(189, "GDVS"), List.of());
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
        int baseId = id;
        List<OperandSource> sources = OPERAND_SOURCES.get(baseId);
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