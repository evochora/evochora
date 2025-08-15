package org.evochora.runtime;

import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Environment;

/**
 * TODO: [Phase 3] Dies ist der Kern der Ausführungsumgebung.
 *  Diese Klasse ist für die Orchestrierung der Ausführung von Organismus-Code
 *  innerhalb einer Umgebung verantwortlich. Sie respektiert die Trennung von
 *  Planung und Ausführung, um zukünftiges Multithreading zu ermöglichen.
 */
public class VirtualMachine {

    private final Environment environment;
    private final Simulation simulation; // TODO: In Phase 3 entfernen

    /**
     * Erstellt eine neue VM, die an eine bestimmte Umgebung gebunden ist.
     *
     * @param simulation Die Simulation, die den Kontext für die Ausführung bereitstellt.
     */
    public VirtualMachine(Simulation simulation) {
        this.simulation = simulation;
        this.environment = simulation.getEnvironment();
    }

    /**
     * Phase 1: Plant die nächste Instruktion für einen Organismus.
     * Liest den Opcode an der aktuellen Position des Organismus und verwendet
     * die Instruction-Registry, um die entsprechende Instruktions-Klasse zu instanziieren.
     *
     * @param organism Der Organismus, für den die Instruktion geplant werden soll.
     * @return Die geplante, aber noch nicht ausgeführte Instruktion.
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
     * Phase 2: Führt eine bereits geplante Instruktion aus.
     * Diese Methode modifiziert potenziell den Zustand des Organismus und der Umgebung.
     *
     * @param instruction Die geplante Instruktion, die ausgeführt werden soll.
     */
    public void execute(Instruction instruction) {
        Organism organism = instruction.getOrganism();
        if (organism.isDead()) {
            return;
        }

        // Logik wurde aus Organism.processTickAction() hierher verschoben
        java.util.List<Integer> rawArgs = organism.getRawArgumentsFromEnvironment(instruction.getLength(), this.environment);
        organism.takeEr(instruction.getCost(organism, this.environment, rawArgs));

        // TODO: [Phase 3] Diese Methode sollte die Simulation nicht direkt durchreichen.
        instruction.execute(this.simulation);

        if (organism.isInstructionFailed()) {
            organism.takeEr(Config.ERROR_PENALTY_COST);
        }

        if (organism.getEr() <= 0) {
            organism.kill("Ran out of energy");
            return;
        }

        if (!organism.shouldSkipIpAdvance()) {
            organism.advanceIpBy(instruction.getLength(), this.environment);
        }
    }
}