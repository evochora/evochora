package org.evochora.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.spi.thermodynamics.IThermodynamicPolicy;
import org.evochora.runtime.thermodynamics.ThermodynamicPolicyManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.ConfigFactory;

/**
 * Contract tests for the effect protocol: what an instruction reports having done to the world
 * has to be what actually happened to the cell.
 * <p>
 * Every case compares the cell before and after against the recorded effects, replayed in order.
 * That comparison is the safeguard against the one way this design can go wrong - an instruction
 * that changes a cell without recording it would be free, and one that records without changing
 * would be charged for nothing. It is deliberately kept out of the virtual machine: deriving the
 * effects from the cell difference instead of from the record would miss a write that stores the
 * same molecule the read took out.
 */
@Tag("unit")
class EffectLogTest {

    private static final long SEED = 42L;
    private static final int ENERGY = 5_000;
    private static final int[] TO_TARGET = {0, 1};

    /** One effect as the policy was asked to price it. */
    private record Effect(boolean isWrite, int moleculeInt, int ownerId, int actorId) {}

    /**
     * Prices nothing and remembers everything it was asked about, which makes the protocol
     * readable from the outside without the runtime having to expose it.
     */
    public static final class RecordingPolicy implements IThermodynamicPolicy {
        static final List<Effect> RECORDED = new ArrayList<>();

        @Override
        public void initialize(com.typesafe.config.Config options) {
            // No rules: this policy observes, it does not price.
        }

        @Override
        public int baseEnergy() {
            return 0;
        }

        @Override
        public int baseEntropy() {
            return 0;
        }

        @Override
        public Thermodynamics priceEffect(boolean isWrite, int moleculeInt, int ownerId, int actorId) {
            RECORDED.add(new Effect(isWrite, moleculeInt, ownerId, actorId));
            return FREE;
        }
    }

    private Environment env;
    private Simulation sim;
    private Organism organism;
    private int[] target;

    @BeforeAll
    static void initInstructions() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        RecordingPolicy.RECORDED.clear();
        env = new Environment(new EnvironmentProperties(new int[]{32, 32}, true));
        com.typesafe.config.Config organismConfig = ConfigFactory.parseMap(Map.of(
                "max-energy", 32767,
                "max-entropy", 8191,
                "error-penalty-cost", 10));
        com.typesafe.config.Config thermoConfig = ConfigFactory.parseString("""
                default {
                  className = "org.evochora.runtime.EffectLogTest$RecordingPolicy"
                  options {}
                }
                """);
        sim = new Simulation(env, new ThermodynamicPolicyManager(thermoConfig), organismConfig, 1);
        sim.setRandomProvider(new SeededRandomProvider(SEED));
        organism = Organism.create(sim, new int[]{0, 0}, ENERGY);
        sim.addOrganism(organism);
        organism.setDp(0, new int[]{0, 0});
        target = organism.getTargetCoordinate(organism.getDp(0), TO_TARGET, env);
    }

    @AfterEach
    void shutdown() {
        sim.shutdown();
    }

    // ===================================================================================
    // The protocol against the cell
    // ===================================================================================

    @Test
    void peekOnAnOccupiedCellRecordsTheReadItPerformed() {
        Molecule stored = new Molecule(Config.TYPE_DATA, 33, 1);
        env.setMolecule(stored, 999, target);
        organism.writeOperand(1, TO_TARGET);
        placeWithRegisters("PEEK", 0, 1);

        tickAndVerify();

        assertThat(RecordingPolicy.RECORDED)
                .containsExactly(new Effect(false, stored.toInt(), 999, organism.getId()));
    }

    @Test
    void peekOnAnEmptyCellRecordsNothing() {
        organism.writeOperand(1, TO_TARGET);
        placeWithRegisters("PEEK", 0, 1);

        tickAndVerify();

        assertThat(organism.isInstructionFailed()).isTrue();
        assertThat(RecordingPolicy.RECORDED).isEmpty();
    }

    @Test
    void pokeOnAnEmptyCellRecordsTheWriteItPerformed() {
        organism.setMr(1);
        int payload = new Molecule(Config.TYPE_DATA, 50).toInt();
        organism.writeOperand(0, payload);
        organism.writeOperand(1, TO_TARGET);
        placeWithRegisters("POKE", 0, 1);

        tickAndVerify();

        assertThat(RecordingPolicy.RECORDED)
                .containsExactly(new Effect(true, new Molecule(Config.TYPE_DATA, 50, 1).toInt(), 0, organism.getId()));
    }

    @Test
    void pokeOnAnOccupiedCellRecordsNothing() {
        env.setMolecule(new Molecule(Config.TYPE_DATA, 1), 999, target);
        organism.writeOperand(0, new Molecule(Config.TYPE_DATA, 50).toInt());
        organism.writeOperand(1, TO_TARGET);
        placeWithRegisters("POKE", 0, 1);

        tickAndVerify();

        assertThat(organism.isInstructionFailed()).isTrue();
        assertThat(RecordingPolicy.RECORDED).isEmpty();
    }

    @Test
    void ppkOnAnOccupiedCellRecordsTheReadAndTheWrite() {
        Molecule stored = new Molecule(Config.TYPE_DATA, 33, 1);
        env.setMolecule(stored, 999, target);
        organism.setMr(1);
        organism.writeOperand(0, new Molecule(Config.TYPE_DATA, 50).toInt());
        organism.writeOperand(1, TO_TARGET);
        placeWithRegisters("PPKR", 0, 1);

        tickAndVerify();

        assertThat(RecordingPolicy.RECORDED).containsExactly(
                new Effect(false, stored.toInt(), 999, organism.getId()),
                new Effect(true, new Molecule(Config.TYPE_DATA, 50, 1).toInt(), 0, organism.getId()));
    }

    @Test
    void ppkOnAnEmptyCellRecordsOnlyTheWrite() {
        organism.setMr(1);
        organism.writeOperand(0, new Molecule(Config.TYPE_DATA, 50).toInt());
        organism.writeOperand(1, TO_TARGET);
        placeWithRegisters("PPKR", 0, 1);

        tickAndVerify();

        // Nothing was consumed, so there is nothing to charge a read for.
        assertThat(RecordingPolicy.RECORDED)
                .containsExactly(new Effect(true, new Molecule(Config.TYPE_DATA, 50, 1).toInt(), 0, organism.getId()));
    }

    /**
     * A write that stores exactly the molecule the read took out leaves the cell unchanged. The
     * protocol still reports both, which is why the effects and not the cell difference decide
     * the price.
     */
    @Test
    void ppkThatWritesBackWhatItReadRecordsBothEffects() {
        Molecule stored = new Molecule(Config.TYPE_CODE, 33);
        env.setMolecule(stored, organism.getId(), target);
        organism.writeOperand(0, stored.toInt());
        organism.writeOperand(1, TO_TARGET);
        placeWithRegisters("PPKR", 0, 1);

        sim.tick();

        assertThat(env.getMolecule(target).toInt()).isEqualTo(stored.toInt());
        assertThat(RecordingPolicy.RECORDED).containsExactly(
                new Effect(false, stored.toInt(), organism.getId(), organism.getId()),
                new Effect(true, stored.toInt(), 0, organism.getId()));
    }

    @Test
    void aWriteIsRecordedInTheFormTheCellStoresItIn() {
        // Marker register 0 turns a written DATA molecule into STATE, and that is what the
        // protocol has to report - the cell holds nothing else afterwards.
        organism.setMr(0);
        organism.writeOperand(0, new Molecule(Config.TYPE_DATA, 50).toInt());
        organism.writeOperand(1, TO_TARGET);
        placeWithRegisters("POKE", 0, 1);

        tickAndVerify();

        assertThat(env.getMolecule(target).type()).isEqualTo(Config.TYPE_STATE);
        assertThat(RecordingPolicy.RECORDED)
                .containsExactly(new Effect(true, new Molecule(Config.TYPE_STATE, 50).toInt(), 0, organism.getId()));
    }


    // ===================================================================================
    // The context carries nothing from one instruction to the next
    // ===================================================================================

    /**
     * The execution context is reused, so an effect left behind by the previous instruction would
     * be charged again. Which organisms share a context depends on the configured parallelism, so
     * such a leak would make the trajectory depend on the thread count and on whether the run was
     * resumed.
     */
    @Test
    void anInstructionSeesNoneOfThePreviousInstructionsEffects() {
        organism.setMr(1);
        organism.writeOperand(0, new Molecule(Config.TYPE_DATA, 50).toInt());
        organism.writeOperand(1, TO_TARGET);
        placeWithRegisters("POKE", 0, 1);

        sim.tick();
        assertThat(RecordingPolicy.RECORDED).hasSize(1);

        // The same cell is now occupied, so the second POKE fails and must leave no effect at all.
        RecordingPolicy.RECORDED.clear();
        organism.setIp(organism.getInitialPosition());
        organism.writeOperand(1, TO_TARGET);
        sim.tick();

        assertThat(organism.isInstructionFailed()).isTrue();
        assertThat(RecordingPolicy.RECORDED).isEmpty();
    }

    /**
     * A read that cannot deliver its value takes nothing: the cell stays as it was, so there is
     * no effect to charge - not even the energy the molecule would have been worth.
     */
    @Test
    void peekThatOverflowsTheDataStackRecordsNothing() {
        Molecule stored = new Molecule(Config.TYPE_ENERGY, 500);
        env.setMolecule(stored, 0, target);
        for (int i = 0; i < Config.DS_MAX_DEPTH; i++) {
            assertThat(organism.pushData(0)).isTrue();
        }
        organism.writeOperand(1, TO_TARGET);
        placeWithRegisters("PEKS", 1, 1);

        int before = env.getMolecule(target).toInt();
        sim.tick();

        assertThat(organism.isInstructionFailed()).isTrue();
        assertThat(env.getMolecule(target).toInt()).as("the cell keeps its molecule").isEqualTo(before);
        assertThat(RecordingPolicy.RECORDED).isEmpty();
    }

    // ===================================================================================
    // Helpers
    // ===================================================================================

    /**
     * Ticks once and checks the recorded effects against what happened to the target cell:
     * replaying them on the cell's previous content has to produce its current content.
     */
    private void tickAndVerify() {
        int before = env.getMolecule(target).toInt();
        sim.tick();
        int after = env.getMolecule(target).toInt();

        int replayed = before;
        for (Effect effect : RecordingPolicy.RECORDED) {
            if (effect.isWrite()) {
                assertThat(Molecule.fromInt(replayed).isEmpty())
                        .as("a write can only land in a cell the instruction left empty")
                        .isTrue();
                replayed = effect.moleculeInt();
            } else {
                assertThat(replayed)
                        .as("a read reports the molecule that stood in the cell")
                        .isEqualTo(effect.moleculeInt());
                replayed = 0;
            }
        }
        assertThat(replayed)
                .as("replaying the recorded effects reproduces the cell")
                .isEqualTo(after);
    }

    /** Places {@code NAME %r0 %r1} at the organism's instruction pointer. */
    private void placeWithRegisters(String name, int first, int second) {
        int[] pos = organism.getInitialPosition();
        env.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName(name)), organism.getId(), pos);
        pos = organism.getNextInstructionPosition(pos, organism.getDv(), env);
        env.setMolecule(new Molecule(Config.TYPE_DATA, first), organism.getId(), pos);
        pos = organism.getNextInstructionPosition(pos, organism.getDv(), env);
        env.setMolecule(new Molecule(Config.TYPE_DATA, second), organism.getId(), pos);
    }
}
