package org.evochora.runtime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.evochora.compiler.api.ProgramArtifact;
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
 * to allow for future multithreading.
 */
public class VirtualMachine {

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
        Molecule molecule = this.environment.getMolecule(organism.getIp());

        if (Config.STRICT_TYPING) {
            if (molecule.type() != Config.TYPE_CODE && !molecule.isEmpty()) {
                // LABEL molecules: skip like NOP (no error)
                if (molecule.type() == Config.TYPE_LABEL) {
                    int nopOpcodeId = Instruction.getInstructionIdByName("NOP");
                    return new org.evochora.runtime.isa.instructions.NopInstruction(organism, nopOpcodeId);
                }
                organism.instructionFailed("Illegal cell type (not CODE) at IP");
                return new org.evochora.runtime.isa.instructions.NopInstruction(organism, molecule.toInt());
            }
        }

        int opcodeId = molecule.value();  // Use value only, not packed int (which includes marker)
        java.util.function.BiFunction<Organism, Environment, Instruction> planner = Instruction.getPlannerById(opcodeId);
        if (planner != null) {
            return planner.apply(organism, this.environment);
        }

        organism.instructionFailed("Unknown opcode: " + opcodeId);
        return new org.evochora.runtime.isa.instructions.NopInstruction(organism, opcodeId);
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

        try {
            // Logic moved from Organism.processTickAction() here
            java.util.List<Integer> rawArgs = organism.getRawArgumentsFromEnvironment(instruction.getLength(this.environment), this.environment);
            
            // Collect register values BEFORE execution (for annotation display)
            Map<Integer, Object> registerValuesBefore = new HashMap<>();
            Optional<InstructionSignature> signatureOpt = Instruction.getSignatureById(instruction.getFullOpcodeId());
            if (signatureOpt.isPresent()) {
                InstructionSignature signature = signatureOpt.get();
                java.util.List<InstructionArgumentType> argTypes = signature.argumentTypes();
                int argIndex = 0;
                
                for (InstructionArgumentType argType : argTypes) {
                    if (argType == InstructionArgumentType.REGISTER) {
                        if (argIndex < rawArgs.size()) {
                            int rawArg = rawArgs.get(argIndex);
                            Molecule molecule = Molecule.fromInt(rawArg);
                            int registerId = molecule.toScalarValue();

                            // Read register value BEFORE execution (DR/PR/FPR)
                            Object registerValue = organism.readOperand(registerId);
                            if (registerValue != null) {
                                registerValuesBefore.put(registerId, registerValue);
                            }
                            // null means invalid register - don't store, frontend shows register name only

                            argIndex++;
                        }
                    } else if (argType == InstructionArgumentType.LOCATION_REGISTER) {
                        if (argIndex < rawArgs.size()) {
                            int rawArg = rawArgs.get(argIndex);
                            Molecule molecule = Molecule.fromInt(rawArg);
                            int registerId = molecule.toScalarValue();
                            
                            // Read location register value BEFORE execution (LR - always int[])
                            // Safely check bounds to avoid failing the instruction during debug data collection
                            // The instruction's own execute() method will handle validation and specific error messages
                            if (registerId >= 0 && registerId < Config.NUM_LOCATION_REGISTERS) {
                                int[] lrValue = organism.getLr(registerId);
                                if (lrValue != null) {
                                    registerValuesBefore.put(registerId, lrValue);
                                } else {
                                    // LR is null - store empty vector with correct dimensions
                                    int dims = this.environment.getShape().length;
                                    registerValuesBefore.put(registerId, new int[dims]);
                                }
                            }
                            
                            argIndex++;
                        }
                    } else if (argType == InstructionArgumentType.VECTOR || 
                               argType == InstructionArgumentType.LABEL) {
                        // VECTOR/LABEL are encoded as multiple arguments in rawArgs (one per dimension)
                        // Skip over all dimension slots to maintain correct argIndex for subsequent arguments
                        int dims = this.environment.getShape().length;
                        argIndex += dims;
                    } else {
                        // IMMEDIATE, LITERAL - no register arguments
                        argIndex++;
                    }
                }
            }
            
            // Track energy and entropy before execution to calculate total changes
            int energyBefore = organism.getEr();
            int entropyBefore = organism.getSr();
            
            // --- Thermodynamic Logic Start ---

            // 1. Resolve operands (idempotent - can be called multiple times safely)
            // Note: resolveOperands only PEEKs stack values, actual POPs happen in commitStackReads()
            List<Instruction.Operand> resolvedOperands = instruction.resolveOperands(this.environment);

            // 2. Commit the stack reads now that we know this instruction will execute
            instruction.commitStackReads();

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

            ExecutionContext context = new ExecutionContext(organism, this.environment, false); // Always run in debug mode
            ProgramArtifact artifact = this.simulation.getProgramArtifacts().get(organism.getProgramId());
            instruction.execute(context, artifact);

            if (organism.isInstructionFailed()) {
                int penalty = this.simulation.getOrganismConfig().getInt("error-penalty-cost");
                organism.takeEr(penalty);
            }

            // Calculate total energy cost and entropy delta
            int energyAfter = organism.getEr();
            int totalEnergyCost = energyBefore - energyAfter;
            int entropyAfter = organism.getSr();
            int totalEntropyDelta = entropyAfter - entropyBefore;

            // Store instruction execution data for history tracking
            Organism.InstructionExecutionData executionData = new Organism.InstructionExecutionData(
                instruction.getFullOpcodeId(),
                rawArgs,
                totalEnergyCost,
                totalEntropyDelta,
                registerValuesBefore
            );
            organism.setLastInstructionExecution(executionData);

            if (organism.getEr() <= 0) {
                organism.kill("Ran out of energy");
                return;
            }

            if (organism.getSr() >= organism.getMaxEntropy()) {
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
}