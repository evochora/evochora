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
                        registerValuesBefore.put(registerId, registerValue);
                        
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
                    // VECTOR/LABEL have no register arguments encoded in rawArgs
                    // Skip the vector/label slots (they are encoded separately in environment)
                    // argIndex is not incremented for VECTOR/LABEL in rawArgs
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
        
        // 1. Resolve operands once for thermodynamics AND execution
        // Note: resolveOperands may modify organism state (stack pop), so it must be called exactly once per instruction
        List<Instruction.Operand> resolvedOperands = instruction.resolveOperands(this.environment);
        instruction.setPreResolvedOperands(resolvedOperands);

        // 2. Determine target info (only for env-modifying instructions that need it)
        Optional<ThermodynamicContext.TargetInfo> targetInfo = Optional.empty();
        if (instruction instanceof IEnvironmentModifyingInstruction) {
            IEnvironmentModifyingInstruction envInstr = (IEnvironmentModifyingInstruction) instruction;
            List<int[]> targets = envInstr.getTargetCoordinates();
            if (targets != null && !targets.isEmpty()) {
                // For simplicity, we only consider the first target for thermodynamics of single-cell ops like PEEK/POKE
                int[] coord = targets.get(0);
                Molecule molecule = this.environment.getMolecule(coord);
                int ownerId = this.environment.getOwnerId(coord);
                targetInfo = Optional.of(new ThermodynamicContext.TargetInfo(coord, molecule, ownerId));
            }
        }

        // 3. Create Context (minimal overhead - record allocation)
        ThermodynamicContext thermoContext = new ThermodynamicContext(
            instruction, organism, this.environment, resolvedOperands, targetInfo
        );

        // 4. Calculate Thermodynamics using Policy (optimized: single call, array lookup)
        IThermodynamicPolicy policy = this.simulation.getPolicyManager().getPolicy(instruction);
        IThermodynamicPolicy.Thermodynamics thermo = policy.getThermodynamics(thermoContext);

        // 5. Apply effects
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
    }
}