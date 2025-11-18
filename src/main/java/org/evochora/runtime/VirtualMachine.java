package org.evochora.runtime;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.InstructionArgumentType;
import org.evochora.runtime.isa.InstructionSignature;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Environment;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The core of the execution environment.
 * This class is responsible for orchestrating the execution of organism code
 * within an environment. It respects the separation of planning and execution
 * to allow for future multithreading.
 */
public class VirtualMachine {

    private final Environment environment;

    /**
     * Creates a new VM bound to a specific environment.
     *
     * @param simulation The simulation that provides the context for execution.
     */
    public VirtualMachine(Simulation simulation) {
        this.environment = simulation.getEnvironment();
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

        int opcodeId = molecule.toInt();
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
     * @param simulation  The simulation that provides the context for execution.
     */
    public void execute(Instruction instruction, Simulation simulation) {
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
        
        // Track energy before execution to calculate total cost
        int energyBefore = organism.getEr();
        
        organism.takeEr(instruction.getCost(organism, this.environment, rawArgs));

        ExecutionContext context = new ExecutionContext(organism, this.environment, false); // Always run in debug mode
        ProgramArtifact artifact = simulation.getProgramArtifacts().get(organism.getProgramId());
        instruction.execute(context, artifact);

        if (organism.isInstructionFailed()) {
            organism.takeEr(Config.ERROR_PENALTY_COST);
        }

        // Calculate total energy cost
        int energyAfter = organism.getEr();
        int energyCost = energyBefore - energyAfter;

        // Store instruction execution data for history tracking
        Organism.InstructionExecutionData executionData = new Organism.InstructionExecutionData(
            instruction.getFullOpcodeId(),
            rawArgs,
            energyCost,
            registerValuesBefore
        );
        organism.setLastInstructionExecution(executionData);

        if (organism.getEr() <= 0) {
            organism.kill("Ran out of energy");
            return;
        }

        if (!organism.shouldSkipIpAdvance()) {
            organism.advanceIpBy(instruction.getLength(this.environment), this.environment);
        }
    }
}