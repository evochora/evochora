package org.evochora.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.test.utils.SimulationTestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for losing a write conflict: the loser fails like any other failed instruction
 * (reason, cost, death checks) but keeps its instruction pointer and retries next tick; the winner
 * is chosen by a per-tick priority that is fair over time and independent of thread count and
 * registration order.
 */
@Tag("unit")
class ConflictLossSemanticsTest {

    private static final long SEED = 42L;
    private static final int[] CELL_X = {0, 1};
    private static final int[] VECTOR_TO_X = {0, 1};
    private static final int ENERGY = 2_000;

    private final List<Simulation> simulations = new ArrayList<>();

    @BeforeAll
    static void initInstructions() {
        Instruction.init();
    }

    @AfterEach
    void shutdownSimulations() {
        simulations.forEach(Simulation::shutdown);
        simulations.clear();
    }

    // ===================================================================================
    // Loser accounting and retry
    // ===================================================================================

    @Test
    void loser_failsWithReasonCostAndHeldInstructionPointer() {
        Contest contest = new Contest(1, false);
        int penalty = contest.sim.getOrganismConfig().getInt("error-penalty-cost");

        contest.sim.tick();

        Organism winner = contest.winner();
        Organism loser = contest.other(winner);
        assertThat(winner.isInstructionFailed()).as(winner.getFailureReason()).isFalse();
        assertThat(loser.isInstructionFailed()).isTrue();
        assertThat(loser.getFailureReason()).isEqualTo(VirtualMachine.LOST_WRITE_CONFLICT);
        // Test fixture: POKI has no base energy and no base entropy, so the loser pays the penalty only
        assertThat(loser.getEr()).isEqualTo(ENERGY - penalty);
        assertThat(loser.getSr()).isEqualTo(0);
        assertThat(loser.getIp()).as("loser keeps its instruction pointer").isEqualTo(loser.getInitialPosition());
        assertThat(winner.getIp()).as("winner advanced").isNotEqualTo(winner.getInitialPosition());
    }

    @Test
    void loser_retriesAndSucceedsOnceContentionIsGone() {
        Contest contest = new Contest(1, false);
        int penalty = contest.sim.getOrganismConfig().getInt("error-penalty-cost");

        contest.sim.tick();
        Organism winner = contest.winner();
        Organism loser = contest.other(winner);
        int winnerCost = ENERGY - winner.getEr();

        // Contention disappears: the winner has moved on, the cell is free again
        contest.env.setMolecule(new Molecule(Config.TYPE_CODE, 0), 0, CELL_X);
        contest.sim.tick();

        assertThat(loser.isInstructionFailed()).as(loser.getFailureReason()).isFalse();
        assertThat(contest.env.getMolecule(CELL_X).toInt()).isEqualTo(contest.payloadOf(loser));
        assertThat(loser.getEr())
                .as("one lost attempt plus one successful write")
                .isEqualTo(ENERGY - penalty - winnerCost);
    }

    @Test
    void loss_isVisibleToTheNextInstructionAsAFailure() {
        Contest contest = new Contest(1, false);
        contest.sim.tick();
        Organism loser = contest.other(contest.winner());

        // The loser's next instruction observes the failed tick. The retry would re-execute the
        // POKI itself, so the pointer is moved onto the IFER guard that follows the POKI.
        int[] guard = contest.placeIferGuard(loser);
        loser.setIp(guard.clone());
        contest.sim.tick();

        assertThat(loser.wasPreviousInstructionFailed()).isTrue();
        assertThat(loser.getIp())
                .as("IFER after a failed tick lets the next instruction execute instead of skipping it")
                .isEqualTo(nextCell(guard));
    }

    // ===================================================================================
    // Winner selection
    // ===================================================================================

    @Test
    void winner_isTheContenderWithTheSmallestTickPriority() {
        List<Integer> winners = contestedWinners(1, false, 32);

        assertThat(winners).as("both contenders win over time").containsOnly(1, 2).contains(1, 2);
    }

    @Test
    void winnerSequence_isIndependentOfRegistrationOrderAndThreadCount() {
        List<Integer> reference = contestedWinners(1, false, 32);

        assertThat(contestedWinners(1, true, 32)).as("reversed registration order").isEqualTo(reference);
        assertThat(contestedWinners(2, false, 32)).as("two threads").isEqualTo(reference);
    }

    // ===================================================================================
    // No starvation
    // ===================================================================================

    @Test
    void repeatedLosses_cannotBeSurvivedForFree() {
        int energy = 40;
        Contest contest = new Contest(1, false, energy);
        int penalty = contest.sim.getOrganismConfig().getInt("error-penalty-cost");
        int tickBound = (energy / penalty) * 2 + 2;

        for (int tick = 0; tick < tickBound && !(contest.a.isDead() && contest.b.isDead()); tick++) {
            contest.rearm();
            contest.sim.tick();
        }

        assertThat(contest.a.isDead()).as("organism 1 dead").isTrue();
        assertThat(contest.b.isDead()).as("organism 2 dead").isTrue();
    }

    // ===================================================================================
    // Regression guard: non-modifying instructions never enter conflict resolution
    // ===================================================================================

    @Test
    void nonModifyingInstructions_neverLoseConflicts() {
        Environment env = new Environment(new EnvironmentProperties(new int[]{32, 32}, true));
        Simulation sim = SimulationTestUtils.createSimulation(env, 1);
        simulations.add(sim);
        sim.setRandomProvider(new SeededRandomProvider(SEED));
        Organism a = Organism.create(sim, new int[]{0, 0}, ENERGY);
        Organism b = Organism.create(sim, new int[]{10, 0}, ENERGY);
        sim.addOrganism(a);
        sim.addOrganism(b);
        placeSeti(env, a, 0, 5);
        placeSeti(env, b, 0, 5);

        sim.tick();

        assertThat(a.isInstructionFailed()).as(a.getFailureReason()).isFalse();
        assertThat(b.isInstructionFailed()).as(b.getFailureReason()).isFalse();
        assertThat(a.getEr()).isEqualTo(b.getEr());
    }

    // ===================================================================================
    // Scenario: two organisms writing different payloads into the same empty cell
    // ===================================================================================

    /**
     * Two organisms whose {@code POKI} targets the same empty cell X with distinguishable
     * payloads. Organism IDs are 1 and 2 in creation order; registration order is configurable.
     */
    private final class Contest {
        final Environment env;
        final Simulation sim;
        final Organism a;
        final Organism b;

        Contest(int parallelism, boolean reverseRegistration) {
            this(parallelism, reverseRegistration, ENERGY);
        }

        Contest(int parallelism, boolean reverseRegistration, int energy) {
            env = new Environment(new EnvironmentProperties(new int[]{32, 32}, true));
            sim = SimulationTestUtils.createSimulation(env, parallelism);
            simulations.add(sim);
            sim.setRandomProvider(new SeededRandomProvider(SEED));
            a = Organism.create(sim, new int[]{0, 0}, energy);
            b = Organism.create(sim, new int[]{10, 0}, energy);
            if (reverseRegistration) {
                sim.addOrganism(b);
                sim.addOrganism(a);
            } else {
                sim.addOrganism(a);
                sim.addOrganism(b);
            }
            arm(a);
            arm(b);
        }

        private void arm(Organism organism) {
            organism.setDp(0, new int[]{0, 0});
            organism.writeOperand(0, payloadOf(organism));
            placeWithVector(env, organism, "POKI", 0, VECTOR_TO_X);
        }

        /** Empties cell X and points both organisms back at their {@code POKI}. */
        void rearm() {
            env.setMolecule(new Molecule(Config.TYPE_CODE, 0), 0, CELL_X);
            a.setIp(a.getInitialPosition());
            b.setIp(b.getInitialPosition());
            a.writeOperand(0, payloadOf(a));
            b.writeOperand(0, payloadOf(b));
        }

        int payloadOf(Organism organism) {
            return new Molecule(Config.TYPE_DATA, 100 + organism.getId()).toInt();
        }

        Organism winner() {
            int written = env.getMolecule(CELL_X).toInt();
            assertThat(written).as("exactly one contender wrote cell X").isIn(payloadOf(a), payloadOf(b));
            return written == payloadOf(a) ? a : b;
        }

        Organism other(Organism organism) {
            return organism == a ? b : a;
        }

        /** Places {@code IFER; SETI %DR1 1} directly behind the organism's POKI and returns the IFER position. */
        int[] placeIferGuard(Organism organism) {
            int[] pos = organism.getInitialPosition();
            for (int i = 0; i < 1 + 1 + VECTOR_TO_X.length; i++) {
                pos = organism.getNextInstructionPosition(pos, organism.getDv(), env);
            }
            int[] guard = pos;
            env.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("IFER")), organism.getId(), guard);
            pos = organism.getNextInstructionPosition(pos, organism.getDv(), env);
            env.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("SETI")), organism.getId(), pos);
            pos = organism.getNextInstructionPosition(pos, organism.getDv(), env);
            env.setMolecule(new Molecule(Config.TYPE_DATA, 1), organism.getId(), pos);
            pos = organism.getNextInstructionPosition(pos, organism.getDv(), env);
            env.setMolecule(new Molecule(Config.TYPE_DATA, 1), organism.getId(), pos);
            return guard;
        }
    }

    /**
     * Runs {@code ticks} contests in one simulation (cell emptied and organisms re-armed before
     * every tick) and returns the winner's ID per tick, checking each against the priority rule.
     */
    private List<Integer> contestedWinners(int parallelism, boolean reverseRegistration, int ticks) {
        Contest contest = new Contest(parallelism, reverseRegistration);
        List<Integer> winners = new ArrayList<>(ticks);
        for (int tick = 0; tick < ticks; tick++) {
            contest.rearm();
            contest.sim.tick();
            Organism winner = contest.winner();
            Organism loser = contest.other(winner);
            long winnerPriority = winner.getRandom().tickStreamSeed();
            long loserPriority = loser.getRandom().tickStreamSeed();
            assertThat(winnerPriority)
                    .as("tick %d: the smaller tick priority wins", tick)
                    .isLessThanOrEqualTo(loserPriority);
            if (winnerPriority == loserPriority) {
                assertThat(winner.getId()).as("ID backstop on equal priority").isLessThan(loser.getId());
            }
            winners.add(winner.getId());
        }
        return winners;
    }

    private static void placeWithVector(Environment env, Organism organism, String name, int register, int[] vector) {
        int[] pos = organism.getIp();
        env.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName(name)), organism.getId(), pos);
        pos = organism.getNextInstructionPosition(pos, organism.getDv(), env);
        env.setMolecule(new Molecule(Config.TYPE_DATA, register), organism.getId(), pos);
        for (int component : vector) {
            pos = organism.getNextInstructionPosition(pos, organism.getDv(), env);
            env.setMolecule(new Molecule(Config.TYPE_DATA, component), organism.getId(), pos);
        }
    }

    private static void placeSeti(Environment env, Organism organism, int register, int value) {
        int[] pos = organism.getIp();
        env.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("SETI")), organism.getId(), pos);
        pos = organism.getNextInstructionPosition(pos, organism.getDv(), env);
        env.setMolecule(new Molecule(Config.TYPE_DATA, register), organism.getId(), pos);
        pos = organism.getNextInstructionPosition(pos, organism.getDv(), env);
        env.setMolecule(new Molecule(Config.TYPE_DATA, value), organism.getId(), pos);
    }

    private static int[] nextCell(int[] pos) {
        return new int[]{pos[0] + 1, pos[1]};
    }
}
