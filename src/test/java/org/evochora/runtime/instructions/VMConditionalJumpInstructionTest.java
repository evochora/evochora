package org.evochora.runtime.instructions;

import static org.assertj.core.api.Assertions.assertThat;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.RegisterBank;
import org.evochora.runtime.isa.instructions.Condition;
import org.evochora.runtime.isa.instructions.ConditionalJumpInstruction;
import org.evochora.runtime.isa.instructions.ConditionalSkipInstruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.test.utils.SimulationTestUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Contains low-level unit tests for the execution of conditional jumps by the virtual machine.
 * Each test places one conditional jump with a label as its last operand and checks after one tick
 * whether the instruction pointer went to the code behind the label or on to the next instruction.
 * The conditions themselves are evaluated by the code the conditional skips share, which their own
 * tests cover in depth; these tests cover every condition once in each direction, the negation, and
 * what a jump does without a matching label.
 */
@Tag("unit")
class VMConditionalJumpInstructionTest {

    private static final int LABEL_HASH = 0x2B3C5 & Config.VALUE_MASK;
    private static final int[] LABEL_POS = {40, 5};
    private static final int[] BEHIND_LABEL = {41, 5};

    private Environment environment;
    private Organism org;
    private Simulation sim;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        environment = new Environment(new int[]{96, 96}, true);
        sim = SimulationTestUtils.createSimulation(environment);
        // A dummy organism first, so that the organism under test does not have ID 0
        Organism.create(sim, new int[]{-1, -1}, 1);
        org = Organism.create(sim, new int[]{5, 5}, 1000);
        sim.addOrganism(org);
    }

    /** Places the label the jumps refer to, with a WAIT behind it to stop there. */
    private void placeLabel() {
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, LABEL_HASH), LABEL_POS);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("WAIT")), BEHIND_LABEL);
    }

    /**
     * Places a conditional jump at the instruction pointer: its condition operands, then the label
     * hash, and a WAIT behind it to stop there when the jump falls through.
     *
     * @return the position of the instruction behind the jump
     */
    private int[] placeJump(String name, Integer... conditionArgs) {
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName(name)), org.getIp());
        int[] pos = org.getIp();
        for (int arg : conditionArgs) {
            pos = org.getNextInstructionPosition(pos, org.getDv(), environment);
            environment.setMolecule(new Molecule(Config.TYPE_DATA, arg), pos);
        }
        pos = org.getNextInstructionPosition(pos, org.getDv(), environment);
        environment.setMolecule(new Molecule(Config.TYPE_DATA, LABEL_HASH), pos);
        int[] behind = org.getNextInstructionPosition(pos, org.getDv(), environment);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("WAIT")), behind);
        return behind;
    }

    private static int data(int value) {
        return new Molecule(Config.TYPE_DATA, value).toInt();
    }

    /**
     * One condition in one direction: the positive opcode, its condition operands, what the test
     * prepares, and whether the condition holds.
     */
    record Case(Condition condition, String opcode, Integer[] args, BiConsumer<Organism, Environment> setup,
                boolean holds) {
        @Override
        public String toString() {
            return opcode + (holds ? " holds" : " does not hold");
        }
    }

    private static Integer[] args(Integer... values) {
        return values;
    }

    /** Points %DR1 at the neighbouring cell above the data pointer and gives that cell an owner. */
    private static BiConsumer<Organism, Environment> neighbourOwnedBy(int ownerId) {
        return (o, env) -> {
            o.writeOperand(1, new int[]{0, 1});
            if (ownerId != 0) {
                env.setOwnerId(ownerId == -1 ? o.getId() : ownerId, o.getTargetCoordinate(o.getDp(0), new int[]{0, 1}, env));
            }
        };
    }

    static Stream<Case> cases() {
        BiConsumer<Organism, Environment> none = (o, env) -> { };
        return Stream.of(
                new Case(Condition.EQUAL, "JFI", args(0, 5), (o, env) -> o.writeOperand(0, data(5)), true),
                new Case(Condition.EQUAL, "JFI", args(0, 6), (o, env) -> o.writeOperand(0, data(5)), false),
                new Case(Condition.LESS_THAN, "JLTI", args(0, 6), (o, env) -> o.writeOperand(0, data(5)), true),
                new Case(Condition.LESS_THAN, "JLTI", args(0, 5), (o, env) -> o.writeOperand(0, data(5)), false),
                new Case(Condition.GREATER_THAN, "JGTI", args(0, 4), (o, env) -> o.writeOperand(0, data(5)), true),
                new Case(Condition.GREATER_THAN, "JGTI", args(0, 5), (o, env) -> o.writeOperand(0, data(5)), false),
                new Case(Condition.TYPE_EQUAL, "JFTI", args(0, 123), (o, env) -> o.writeOperand(0, data(0)), true),
                new Case(Condition.TYPE_EQUAL, "JFTI", args(0, 123),
                        (o, env) -> o.writeOperand(0, new Molecule(Config.TYPE_ENERGY, 7).toInt()), false),
                new Case(Condition.MINE, "JFMR", args(1), neighbourOwnedBy(-1), true),
                new Case(Condition.MINE, "JFMR", args(1), neighbourOwnedBy(0), false),
                new Case(Condition.PASSABLE, "JFPR", args(1), neighbourOwnedBy(0), true),
                new Case(Condition.PASSABLE, "JFPR", args(1), (o, env) -> {
                    o.writeOperand(1, new int[]{0, 1});
                    env.setMolecule(new Molecule(Config.TYPE_STRUCTURE, 1), 4242,
                            o.getTargetCoordinate(o.getDp(0), new int[]{0, 1}, env));
                }, false),
                new Case(Condition.FOREIGN, "JFFR", args(1), neighbourOwnedBy(999), true),
                new Case(Condition.FOREIGN, "JFFR", args(1), neighbourOwnedBy(0), false),
                new Case(Condition.VACANT, "JFVR", args(1), neighbourOwnedBy(0), true),
                new Case(Condition.VACANT, "JFVR", args(1), neighbourOwnedBy(999), false),
                new Case(Condition.PREVIOUS_FAILED, "JFER", args(), (o, env) -> o.instructionFailed("simulated failure"), true),
                new Case(Condition.PREVIOUS_FAILED, "JFER", args(), none, false),
                new Case(Condition.HOLDS_POSITION, "JFSL", args(RegisterBank.LR.base),
                        (o, env) -> o.writeLocationOperand(RegisterBank.LR.base, new int[]{3, 4}), true),
                new Case(Condition.HOLDS_POSITION, "JFSL", args(RegisterBank.LR.base), none, false),
                new Case(Condition.GREATER_THAN_DRAW, "QGTI", args(0, 100_000), (o, env) -> o.writeOperand(0, data(100_000)), true),
                new Case(Condition.GREATER_THAN_DRAW, "QGTI", args(0, 100_000), (o, env) -> o.writeOperand(0, data(0)), false),
                new Case(Condition.LESS_THAN_DRAW, "QLTI", args(0, 0), (o, env) -> o.writeOperand(0, data(-1)), true),
                new Case(Condition.LESS_THAN_DRAW, "QLTI", args(0, 0), (o, env) -> o.writeOperand(0, data(0)), false),
                new Case(Condition.WITHIN_BODY, "JFBR", args(1), (o, env) -> {
                    o.writeOperand(1, new int[]{1, 0});
                    env.setOwnerId(o.getId(), new int[]{3, 5});
                    env.setOwnerId(o.getId(), new int[]{8, 5});
                }, true),
                new Case(Condition.WITHIN_BODY, "JFBR", args(1), (o, env) -> o.writeOperand(1, new int[]{1, 0}), false),
                new Case(Condition.EXISTS, "JFXR", args(1), neighbourOwnedBy(0), true));
    }

    /**
     * Every condition is tested in both directions, except that a cell which does not exist
     * needs a bounded world and has a test of its own.
     */
    @Test
    void everyConditionIsCovered() {
        Set<Condition> holding = cases().filter(Case::holds).map(Case::condition)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Condition.class)));
        Set<Condition> notHolding = cases().filter(c -> !c.holds()).map(Case::condition)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Condition.class)));
        assertThat(holding).containsExactlyInAnyOrder(Condition.values());
        assertThat(notHolding).containsExactlyInAnyOrder(
                EnumSet.complementOf(EnumSet.of(Condition.EXISTS)).toArray(Condition[]::new));
    }

    /**
     * A conditional jump goes to the code behind its label when the condition holds and on to the
     * next instruction when it does not, without failing either way.
     */
    @ParameterizedTest
    @MethodSource("cases")
    void jumpsExactlyWhenTheConditionHolds(Case c) {
        placeLabel();
        c.setup().accept(org, environment);
        int[] behindJump = placeJump(c.opcode(), c.args());

        sim.tick();

        assertThat(org.getIp()).as(c.toString()).isEqualTo(c.holds() ? BEHIND_LABEL : behindJump);
        assertThat(org.isInstructionFailed()).as("failed: %s", org.getFailureReason()).isFalse();
    }

    /**
     * The negated jump holds exactly when its positive twin does not.
     */
    @Test
    void theNegatedJumpTakesTheOtherWay() {
        placeLabel();
        org.writeOperand(0, data(5));
        int[] behindJump = placeJump("JNI", 0, 5);

        sim.tick();

        assertThat(org.getIp()).as("JNI with equal values").isEqualTo(behindJump);

        setUp();
        placeLabel();
        org.writeOperand(0, data(5));
        placeJump("JNI", 0, 6);

        sim.tick();

        assertThat(org.getIp()).as("JNI with different values").isEqualTo(BEHIND_LABEL);
    }

    /**
     * A jump whose condition holds and that finds no matching label fails as JMPI does, and
     * execution goes on with the next instruction.
     */
    @Test
    void aHoldingJumpWithoutALabelFails() {
        org.writeOperand(0, data(5));
        int[] behindJump = placeJump("JFI", 0, 5);

        sim.tick();

        assertThat(org.isInstructionFailed()).isTrue();
        assertThat(org.getFailureReason()).contains("JFI: No matching label found for hash");
        assertThat(org.getIp()).isEqualTo(behindJump);
    }

    /**
     * A jump whose condition does not hold looks up no label, so a missing one does no harm.
     */
    @Test
    void aJumpThatDoesNotHoldNeedsNoLabel() {
        org.writeOperand(0, data(5));
        int[] behindJump = placeJump("JFI", 0, 6);

        sim.tick();

        assertThat(org.isInstructionFailed()).as("failed: %s", org.getFailureReason()).isFalse();
        assertThat(org.getIp()).isEqualTo(behindJump);
    }

    /**
     * A stack comparison with too few values on the stack fails as the conditional skip does: the
     * test cannot be evaluated and does not hold, so the jump is not taken.
     */
    @Test
    void aStackComparisonWithTooFewValuesFails() {
        placeLabel();
        org.getDataStack().push(data(5));
        int[] behindJump = placeJump("JFS");

        sim.tick();

        assertThat(org.isInstructionFailed()).isTrue();
        assertThat(org.getFailureReason()).isEqualTo("Data stack underflow for JFS");
        assertThat(org.getIp()).isEqualTo(behindJump);
    }

    /**
     * A stack comparison takes its two values from the stack and jumps on them.
     */
    @Test
    void aStackComparisonJumpsOnTheTwoTopValues() {
        placeLabel();
        org.getDataStack().push(data(5));
        org.getDataStack().push(data(5));
        placeJump("JFS");

        sim.tick();

        assertThat(org.getIp()).isEqualTo(BEHIND_LABEL);
        assertThat(org.getDataStack()).isEmpty();
    }

    /**
     * The negation of a stack comparison with too few values holds: it jumps, and the value that
     * was on the stack is consumed as it is for every processed instruction.
     */
    @Test
    void theNegatedStackComparisonWithTooFewValuesJumps() {
        placeLabel();
        org.getDataStack().push(data(5));
        placeJump("JNS");

        sim.tick();

        assertThat(org.getFailureReason()).isEqualTo("Data stack underflow for JNS");
        assertThat(org.getIp()).isEqualTo(BEHIND_LABEL);
        assertThat(org.getDataStack()).isEmpty();
    }

    /**
     * An operand that names no register cannot be read, and the test does not hold: the jump
     * stays, its negation jumps, and both book the failure with its penalty, once.
     */
    @Test
    void aTestThatCannotBeEvaluatedDoesNotHold() {
        placeLabel();
        int[] behindJump = placeJump("JFI", -1, 5);
        int penalty = sim.getOrganismConfig().getInt("error-penalty-cost");

        sim.tick();

        assertThat(org.getFailureReason()).isEqualTo("Invalid register ID: -1");
        assertThat(org.getIp()).as("JFI stays").isEqualTo(behindJump);
        assertThat(org.getEr()).isStrictlyBetween(1000 - 2 * penalty, 1000 - penalty + 1);

        setUp();
        placeLabel();
        placeJump("JNI", -1, 5);

        sim.tick();

        assertThat(org.getFailureReason()).isEqualTo("Invalid register ID: -1");
        assertThat(org.getIp()).as("JNI jumps").isEqualTo(BEHIND_LABEL);
        assertThat(org.getEr()).isStrictlyBetween(1000 - 2 * penalty, 1000 - penalty + 1);
    }

    /**
     * Returns the negation of a conditional jump: the jump with the operation of the negation of
     * its twin skip, which is the skip with the same operation and the same operands before the
     * label.
     */
    private static String negationOfJump(int jumpId) {
        List<Instruction.OperandSource> sources = Instruction.getOperandSourcesById(jumpId);
        List<Instruction.OperandSource> skipSources = sources.subList(0, sources.size() - 1);
        int twinSkip = opcodeWith(ConditionalSkipInstruction.class, Instruction.getOperationById(jumpId), skipSources);
        String negatedSkip = ConditionalSkipInstruction.negationOf(Instruction.getInstructionNameById(twinSkip)).orElseThrow();
        int negatedOperation = Instruction.getOperationById(Instruction.getInstructionIdByName(negatedSkip));
        return Instruction.getInstructionNameById(opcodeWith(ConditionalJumpInstruction.class, negatedOperation, sources));
    }

    private static int opcodeWith(Class<? extends Instruction> kind, int operation, List<Instruction.OperandSource> sources) {
        return Instruction.getAllInstructions().keySet().stream()
                .filter(id -> Instruction.getInstructionClassById(id) == kind
                        && Instruction.getOperationById(id) == operation
                        && Instruction.getOperandSourcesById(id).equals(sources))
                .findFirst().orElseThrow();
    }

    /**
     * Of a conditional jump and its negation exactly one jumps, on a test that cannot be
     * evaluated too. Every pair whose operands can fail to be read is run with every register
     * operand naming no register and an empty stack; the vector operands are sound. Which of the
     * two is the positive form, and that it stays, the tests above show.
     */
    @Test
    void ofEveryPairExactlyOneJumpsOnATestThatCannotBeEvaluated() {
        List<String> examined = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : Instruction.getAllInstructions().entrySet()) {
            if (Instruction.getInstructionClassById(entry.getKey()) != ConditionalJumpInstruction.class) {
                continue;
            }
            String name = entry.getValue();
            String negation = negationOfJump(entry.getKey());
            assertThat(negation).as("negation of %s", name).isNotEqualTo(name);
            if (name.compareTo(negation) > 0) {
                continue;
            }
            List<Integer> conditionArgs = new ArrayList<>();
            boolean canFail = false;
            for (Instruction.OperandSource source : Instruction.getOperandSourcesById(entry.getKey())) {
                switch (source) {
                    case REGISTER, LOCATION_REGISTER -> { conditionArgs.add(-1); canFail = true; }
                    case STACK -> canFail = true;
                    case IMMEDIATE -> conditionArgs.add(5);
                    case VECTOR -> { conditionArgs.add(0); conditionArgs.add(1); }
                    case LABEL -> { }
                }
            }
            if (!canFail) {
                continue;
            }
            boolean oneJumps = jumpsOnFailure(name, conditionArgs.toArray(Integer[]::new));
            boolean otherJumps = jumpsOnFailure(negation, conditionArgs.toArray(Integer[]::new));
            assertThat(oneJumps).as("exactly one of %s and %s jumps on a test that cannot be evaluated", name, negation)
                    .isNotEqualTo(otherJumps);
            examined.add(name);
        }
        assertThat(examined).contains("JFR", "JFI", "JFS", "JFMR", "JFMS", "JFSL", "JGER", "JFBR", "JFXR");
    }

    /** Runs one jump with the given condition arguments in a fresh simulation and tells whether it jumped. */
    private boolean jumpsOnFailure(String name, Integer... conditionArgs) {
        setUp();
        placeLabel();
        int[] behindJump = placeJump(name, conditionArgs);

        sim.tick();

        assertThat(org.isInstructionFailed()).as("%s failed", name).isTrue();
        int[] ip = org.getIp();
        assertThat(ip).as("%s went to the label or on", name).isIn(BEHIND_LABEL, behindJump);
        return Arrays.equals(ip, BEHIND_LABEL);
    }

    /**
     * In a bounded world the argument cells of a jump on the last cells can lie beyond the edge:
     * the test cannot be evaluated, and the label cannot be read either, so neither the jump nor
     * its negation goes anywhere; the next instruction would lie beyond the edge, and the pointer
     * is recovered in the same tick with the one penalty of the failure.
     */
    @Test
    void aJumpWithArgumentCellsBeyondTheEdgeRecoversThePointer() {
        assertRecoveredAtTheEdge("JFI");
        assertRecoveredAtTheEdge("JNI");
    }

    private void assertRecoveredAtTheEdge(String name) {
        environment = new Environment(new int[]{96, 96}, false);
        sim = SimulationTestUtils.createSimulation(environment);
        Organism.create(sim, new int[]{-1, -1}, 1);
        int[] lastCell = new int[]{95, 5};
        org = Organism.create(sim, lastCell.clone(), 1000);
        sim.addOrganism(org);
        placeLabel();
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName(name)), lastCell);
        int penalty = sim.getOrganismConfig().getInt("error-penalty-cost");

        sim.tick();

        assertThat(org.getFailureReason()).as(name).isEqualTo(Instruction.ARGUMENT_CELL_BEYOND_THE_EDGE);
        assertThat(org.getIp()).as(name).isEqualTo(lastCell);
        assertThat(org.getCallStack()).as(name).isEmpty();
        assertThat(org.getEr()).as(name).isStrictlyBetween(1000 - 2 * penalty, 1000 - penalty + 1);
    }

    /**
     * Beyond the edge of a bounded world a cell does not exist, and a jump on its existence falls
     * through.
     */
    @Test
    void aCellBeyondTheEdgeDoesNotExist() {
        environment = new Environment(new int[]{96, 96}, false);
        sim = SimulationTestUtils.createSimulation(environment);
        Organism.create(sim, new int[]{-1, -1}, 1);
        org = Organism.create(sim, new int[]{5, 0}, 1000);
        sim.addOrganism(org);
        placeLabel();
        org.writeOperand(1, new int[]{0, -1});
        int[] behindJump = placeJump("JFXR", 1);

        sim.tick();

        assertThat(org.isInstructionFailed()).as("failed: %s", org.getFailureReason()).isFalse();
        assertThat(org.getIp()).isEqualTo(behindJump);
    }
}
