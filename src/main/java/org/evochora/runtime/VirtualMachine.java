package org.evochora.runtime;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Environment;

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
        organism.takeEr(instruction.getCost(organism, this.environment, rawArgs));

        ExecutionContext context = new ExecutionContext(organism, this.environment, false); // Always run in debug mode
        ProgramArtifact artifact = simulation.getProgramArtifacts().get(organism.getProgramId());
        instruction.execute(context, artifact);

        if (organism.isInstructionFailed()) {
            organism.takeEr(Config.ERROR_PENALTY_COST);
        }

        if (organism.getEr() <= 0) {
            organism.kill("Ran out of energy");
            return;
        }

        if (!organism.shouldSkipIpAdvance()) {
            organism.advanceIpBy(instruction.getLength(this.environment), this.environment);
        }
    }
}