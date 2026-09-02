package org.evochora.runtime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.IEnvironmentModifyingInstruction;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.InstructionArgumentType;
import org.evochora.runtime.isa.InstructionSignature;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.spi.thermodynamics.IThermodynamicPolicy;
import org.evochora.runtime.spi.thermodynamics.ThermodynamicContext;

/**
 * The core of the execution environment.
 * This class is responsible for orchestrating the execution of organism code
 * within an environment. It respects the separation of planning and execution
 * to allow for multithreading.
 * <p>
 * <b>Thread safety:</b> Stateless after construction. Safe for concurrent use from multiple
 * threads, provided each organism is only accessed by one thread at a time.
 */
public class VirtualMachine {

    /**
     * Failure reason recorded for an instruction that lost a write conflict. The instruction was
     * never executed against the environment and is retried in the next tick.
     */
    public static final String LOST_WRITE_CONFLICT = "Lost write conflict";

    private final Environment environment;
    private final Simulation simulation; // Store simulation reference

    /**
     * Creates a new VM bound to a specific environment.
     *
     * @param simulation The simulation that provides the context for execution.
     */
    public VirtualMachine(Simulation simulation) {
        this.environment = simulation.getEnvironment();
        this.simulation = simulation;
    }

    /**
     * Phase 1: Plans the next instruction for an organism.
     * Reads the opcode at the organism's current instruction pointer and uses
     * the instruction registry to instantiate the corresponding instruction class.
     *
     * @param organism The organism for which the instruction is to be planned.
     * @return The planned, but not yet executed, instruction.
     */
    public Instruction plan(Organism organism) {
        organism.resetTickState();

        // Flat-index molecule lookup to avoid coordinate-based getNormalizedCoordinate
        int[] ip = organism.getIp();
        int flatIp = 0;
        for (int i = 0; i < ip.length; i++) {
            flatIp += ip[i] * this.environment.properties.getStride(i);
        }
        int rawMol = this.environment.getMoleculeInt(flatIp);

        Instruction instruction;

        if (Config.STRICT_TYPING) {
            int type = rawMol & Config.TYPE_MASK;
            if (type != Config.TYPE_CODE && rawMol != 0) {
                // Non-CODE molecules: treat as NOP (will be skipped by skipNopCells)
                int nopOpcodeId = Instruction.getInstructionIdByName("NOP");
                instruction = new org.evochora.runtime.isa.instructions.NopInstruction(organism, nopOpcodeId);
                instruction.resolveOperands(this.environment);
                return instruction;
            }
        }

        int opcodeId = Molecule.extractSignedValue(rawMol);
        Instruction.InstructionFactory factory = Instruction.getPlannerById(opcodeId);
        if (factory != null) {
            instruction = factory.create(organism, opcodeId);
            // Resolve operands in Plan phase for conflict resolution and interception
            instruction.resolveOperands(this.environment);
            return instruction;
        }

        organism.instructionFailed("Unknown opcode: " + opcodeId);
        instruction = new org.evochora.runtime.isa.instructions.NopInstruction(organism, opcodeId);
        instruction.resolveOperands(this.environment);
        return instruction;
    }

    /**
     * Phase 2: Executes a previously planned instruction.
     * This method potentially modifies the state of the organism and the environment.
     *
     * @param instruction The planned instruction to be executed.
     */
    public void execute(Instruction instruction) {
        Organism organism = instruction.getOrganism();
        if (organism.isDead()) {
            return;
        }

        // A conflict loser is booked as a failure but not executed; it leaves no execution
        // record, so the argument and register capture below is skipped for it.
        boolean lostConflict = instruction.getConflictStatus() == Instruction.ConflictResolutionStatus.LOST_PRIORITY;

        // Track energy and entropy before execution to calculate total changes
        int energyBefore = organism.getEr();
        int entropyBefore = organism.getSr();

        // The register map is consumed only by observers of sampled ticks; the capture
        // flag spares all other ticks its per-instruction boxing (signature lookup,
        // register reads, boxed map). The rest of the record is kept every tick, so an
        // organism dying between two samples still shows the instruction it died of —
        // deliberately without its register values, which were not collected on that
        // tick. This gap could be closed without per-tick boxing by copying the argument
        // register values into a reusable per-organism buffer on every tick and building
        // the map only on sampled ticks and on death, at the price of the per-tick
        // signature lookup, register reads and copies.
        boolean captureRegisterValues = !lostConflict && this.simulation.isCaptureExecutionDetails();

        int[] rawArgs = null;
        Map<Integer, Object> registerValuesBefore = null;

        try {
            // --- Thermodynamic Logic Start ---

            // 1. Resolve operands (idempotent - can be called multiple times safely)
            // Note: resolveOperands only PEEKs stack values, actual POPs happen in commitStackReads()
            List<Instruction.Operand> resolvedOperands = instruction.resolveOperands(this.environment);

            if (!lostConflict) {
                // Shares the array resolveOperands filled: an instruction's argument
                // cells are read once, and every consumer works from that one read.
                rawArgs = instruction.getRawArguments();
            }
            if (captureRegisterValues) {
                // Collect register values BEFORE execution (for annotation display)
                registerValuesBefore = collectRegisterValues(organism, instruction.getFullOpcodeId(), rawArgs);
            }

            // 2. Commit the stack reads now that we know this instruction will execute. A conflict
            //    loser consumes nothing: it retries with the same operands next tick.
            if (!lostConflict) {
                instruction.commitStackReads();
            }

            // 3. Determine target info (only for env-modifying instructions that need it)
            Optional<ThermodynamicContext.TargetInfo> targetInfo = Optional.empty();
            if (instruction instanceof IEnvironmentModifyingInstruction envInstr) {
                List<int[]> targets = envInstr.getTargetCoordinates();
                if (targets != null && !targets.isEmpty()) {
                    // For simplicity, we only consider the first target for thermodynamics of single-cell ops like PEEK/POKE
                    int[] coord = targets.get(0);
                    Molecule molecule = this.environment.getMolecule(coord);
                    int ownerId = this.environment.getOwnerId(coord);
                    targetInfo = Optional.of(new ThermodynamicContext.TargetInfo(coord, molecule, ownerId));
                }
            }

            // 4. Create Context (minimal overhead - record allocation)
            ThermodynamicContext thermoContext = new ThermodynamicContext(
                instruction, organism, this.environment, resolvedOperands, targetInfo
            );

            // 5. Calculate Thermodynamics using Policy (optimized: single call, array lookup)
            IThermodynamicPolicy policy = this.simulation.getPolicyManager().getPolicy(instruction);
            IThermodynamicPolicy.Thermodynamics thermo = policy.getThermodynamics(thermoContext);

            // 6. Apply effects
            // Energy: positive = consumption (takeEr), negative = gain (addEr with clamping)
            int energyCost = thermo.energyCost();
            if (energyCost > 0) {
                organism.takeEr(energyCost);
            } else if (energyCost < 0) {
                organism.addEr(-energyCost); // addEr clamps to maxEnergy
            }
            organism.addSr(thermo.entropyDelta());
            
            // --- Thermodynamic Logic End ---

            if (lostConflict) {
                // Booked like any failed instruction (penalty, death checks below), but the
                // instruction pointer is held so the write is retried next tick.
                organism.instructionFailed(LOST_WRITE_CONFLICT);
                organism.setSkipIpAdvance(true);
            } else {
                ExecutionContext context = new ExecutionContext(organism, this.environment, false); // Always run in debug mode
                instruction.execute(context);
            }

            if (organism.isInstructionFailed()) {
                int penalty = this.simulation.getOrganismConfig().getInt("error-penalty-cost");
                organism.takeEr(penalty);
            }

            // Calculate total energy cost and entropy delta
            int energyAfter = organism.getEr();
            int totalEnergyCost = energyBefore - energyAfter;
            int entropyAfter = organism.getSr();
            int totalEntropyDelta = entropyAfter - entropyBefore;

            // Store instruction execution data for history tracking. A conflict loser was not
            // executed, so it leaves no execution record; its failure reason is the trace.
            if (!lostConflict) {
                Organism.InstructionExecutionData executionData = new Organism.InstructionExecutionData(
                    instruction.getFullOpcodeId(),
                    rawArgs,
                    totalEnergyCost,
                    totalEntropyDelta,
                    registerValuesBefore
                );
                organism.setLastInstructionExecution(executionData);
            }

            if (organism.getEr() <= 0) {
                organism.kill("Ran out of energy");
                return;
            }

            // Strictly greater: max-entropy is a limit the organism may reach and still live,
            // as the assembly specification and the overview both describe it. Energy is the
            // other way round — zero is already fatal.
            if (organism.getSr() > organism.getMaxEntropy()) {
                organism.kill("Entropy limit exceeded");
                return;
            }

            if (!organism.shouldSkipIpAdvance()) {
                organism.advanceIpBy(instruction.getLength(this.environment), this.environment);
            }
        } catch (Exception e) {
            // Global Catch-All to prevent simulation crash
            organism.instructionFailed("VM Runtime Error: " + e);

            // Apply penalty
            int penalty = this.simulation.getOrganismConfig().getInt("error-penalty-cost");
            organism.takeEr(penalty);

            // A throwing instruction still leaves an execution record: what ran and what it
            // cost (penalty included) stays observable, even if the organism dies of it.
            if (!lostConflict) {
                organism.setLastInstructionExecution(new Organism.InstructionExecutionData(
                    instruction.getFullOpcodeId(),
                    rawArgs,
                    energyBefore - organism.getEr(),
                    organism.getSr() - entropyBefore,
                    registerValuesBefore
                ));
            }

            if (organism.getEr() <= 0) {
                organism.kill("Ran out of energy");
                return;
            }

            // Ensure IP advances so we don't get stuck in a loop on the same failing instruction
            if (!organism.shouldSkipIpAdvance()) {
                try {
                    organism.advanceIpBy(instruction.getLength(this.environment), this.environment);
                } catch (Exception ex) {
                    // If even advancing fails (e.g. strict math error in geometry), kill the organism
                    organism.kill("Fatal VM Error: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Peeks at the instruction at the organism's current IP without executing it.
     * Returns the opcode, raw arguments, and current register values that would be
     * the "before" state for that instruction's execution.
     *
     * @param organism The organism whose next instruction to peek at.
     * @return The instruction data, or {@code null} if the molecule at IP is not a valid instruction.
     */
    public Organism.InstructionExecutionData peekNextInstruction(Organism organism) {
        if (organism.isDead()) {
            return null;
        }

        int[] ip = organism.getIp();
        int flatIp = 0;
        for (int i = 0; i < ip.length; i++) {
            flatIp += ip[i] * this.environment.properties.getStride(i);
        }
        int rawMol = this.environment.getMoleculeInt(flatIp);
        if (rawMol == 0) {
            return null;
        }
        if (Config.STRICT_TYPING && (rawMol & Config.TYPE_MASK) != Config.TYPE_CODE) {
            return null;
        }

        int opcodeId = Molecule.extractSignedValue(rawMol);
        if (Instruction.getPlannerById(opcodeId) == null) {
            return null;
        }

        int length = Instruction.getInstructionLengthById(opcodeId, this.environment);
        int[] rawArgs = organism.getRawArgumentsFromEnvironment(
                length, this.environment, organism.getIp(), organism.getDv());
        Map<Integer, Object> registerValues = collectRegisterValues(organism, opcodeId, rawArgs);

        return new Organism.InstructionExecutionData(opcodeId, rawArgs, 0, 0, registerValues);
    }

    /**
     * Collects register values for the given instruction's register arguments.
     * Used both by {@link #execute(Instruction)} (to capture values before execution)
     * and by {@link #peekNextInstruction(Organism)} (to capture current values as preview).
     *
     * @param organism The organism whose registers to read.
     * @param opcodeId The full opcode ID of the instruction.
     * @param rawArgs  The raw argument values from the environment.
     * @return A map from register ID to register value for all register arguments.
     */
    private Map<Integer, Object> collectRegisterValues(Organism organism, int opcodeId, int[] rawArgs) {
        Map<Integer, Object> registerValues = new HashMap<>();
        Optional<InstructionSignature> signatureOpt = Instruction.getSignatureById(opcodeId);
        if (signatureOpt.isEmpty()) {
            return registerValues;
        }

        InstructionSignature signature = signatureOpt.get();
        java.util.List<InstructionArgumentType> argTypes = signature.argumentTypes();
        int argIndex = 0;

        for (InstructionArgumentType argType : argTypes) {
            if (argType == InstructionArgumentType.REGISTER) {
                if (argIndex < rawArgs.length) {
                    int registerId = Molecule.extractSignedValue(rawArgs[argIndex]);

                    // Read register value (DR/PDR/FDR)
                    Object registerValue = organism.readOperand(registerId);
                    if (registerValue != null) {
                        registerValues.put(registerId, registerValue);
                    }
                    // null means invalid register - don't store, frontend shows register name only

                    argIndex++;
                }
            } else if (argType == InstructionArgumentType.LOCATION_REGISTER) {
                if (argIndex < rawArgs.length) {
                    int registerId = Molecule.extractSignedValue(rawArgs[argIndex]);

                    // Read location register value (identical to REGISTER branch)
                    Object lrValue = organism.readOperand(registerId);
                    if (lrValue != null) {
                        registerValues.put(registerId, lrValue);
                    }

                    argIndex++;
                }
            } else if (argType == InstructionArgumentType.VECTOR) {
                // A vector is the one argument spread over several slots, one per dimension.
                // Skipping all of them keeps the index right for whatever follows.
                int dims = this.environment.properties.getDimensions();
                argIndex += dims;
            } else {
                // IMMEDIATE, LITERAL, LABEL - one slot, and no register to read. A label is a
                // hash and therefore a single molecule, like any other non-vector argument.
                argIndex++;
            }
        }

        return registerValues;
    }
}