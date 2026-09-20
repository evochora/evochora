package org.evochora.runtime.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GenomeFlow}.
 * <p>
 * Every case writes one row of code by hand, builds the reading frame for it and asks the flow one
 * question about it.
 */
@Tag("unit")
class GenomeFlowTest {

    private static final int ORGANISM_ID = 7;
    private static final int ROW = 4;
    private static final int DV_DIM = 0;
    private static final int[] FORWARD = new int[]{1, 0};
    private static final int[] BACKWARD = new int[]{-1, 0};
    private static final int TOLERANCE = 2;
    private static final int LABEL_VALUE = 0b1010_1010_1010;

    private Environment env;
    private GenomeFrame frame;
    private GenomeFlow flow;

    @BeforeAll
    static void initInstructionSet() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        env = new Environment(new int[]{64, 32}, false);
        frame = new GenomeFrame();
        flow = new GenomeFlow();
    }

    /** Places a molecule owned by the organism under test. */
    private void place(int x, Molecule molecule) {
        env.setMolecule(molecule, ORGANISM_ID, new int[]{x, ROW});
    }

    /** Places the opcode cell of the named instruction. */
    private void opcode(int x, String name) {
        place(x, new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName(name)));
    }

    /** Places a label. */
    private void label(int x, int value) {
        place(x, new Molecule(Config.TYPE_LABEL, value));
    }

    /** Places a label reference. */
    private void labelRef(int x, int value) {
        place(x, new Molecule(Config.TYPE_LABELREF, value));
    }

    /** Places {@code SETI %DR0 DATA:1}, three cells, after which execution continues. */
    private void ordinaryInstruction(int x) {
        opcode(x, "SETI");
        place(x + 1, new Molecule(Config.TYPE_REGISTER, 0));
        place(x + 2, new Molecule(Config.TYPE_DATA, 1));
    }

    /** Places {@code IFI %DR0 DATA:0}, three cells, a conditional. */
    private void conditional(int x) {
        opcode(x, "IFI");
        place(x + 1, new Molecule(Config.TYPE_REGISTER, 0));
        place(x + 2, new Molecule(Config.TYPE_DATA, 0));
    }

    /** Places an instruction with one label operand, two cells. */
    private void withLabelOperand(int x, String name, int value) {
        opcode(x, name);
        labelRef(x + 1, value);
    }

    /** Builds the frame, entering at the given position. */
    private void build(int initialX, int[] dv) {
        frame.build(env, ORGANISM_ID, new int[]{initialX, ROW}, dv);
    }

    /** Asks whether execution runs into the cell at {@code x}, the row starting at {@code rowStart}. */
    private boolean reached(int x, int rowStart) {
        return flow.reachedByFallThrough(env, frame, new int[]{x, ROW}, DV_DIM, 1, x - rowStart);
    }

    // ---- reachedByFallThrough ----

    /**
     * An empty cell behind a label and an ordinary instruction: execution runs on into it.
     */
    @Test
    void cellBehindAnOrdinaryInstructionIsReached() {
        label(2, LABEL_VALUE);
        ordinaryInstruction(3);
        place(20, new Molecule(Config.TYPE_DATA, 0));
        build(2, FORWARD);

        assertThat(reached(8, 2)).isTrue();
    }

    /**
     * An empty cell behind an unconditional jump: nothing runs on into it, although code stands
     * before the jump.
     */
    @Test
    void cellBehindAnUnconditionalJumpIsNotReached() {
        label(2, LABEL_VALUE);
        ordinaryInstruction(3);
        withLabelOperand(6, "JMPI", LABEL_VALUE);
        place(20, new Molecule(Config.TYPE_DATA, 0));
        build(2, FORWARD);

        assertThat(reached(10, 2)).isFalse();
    }

    /**
     * A return never falls through, a call does: its return comes back to the cell behind it, so
     * the cells behind a call are reached and those behind a return are not.
     */
    @Test
    void cellBehindAReturnIsNotReachedAndBehindACallIs() {
        label(2, LABEL_VALUE);
        opcode(3, "RET");
        place(30, new Molecule(Config.TYPE_DATA, 0));
        build(2, FORWARD);
        assertThat(reached(6, 2)).isFalse();

        withLabelOperand(3, "CALL", LABEL_VALUE);
        build(2, FORWARD);
        assertThat(reached(8, 2)).isTrue();
    }

    /**
     * A jump that stands behind a conditional is skipped when the test fails, so execution runs on
     * past it into the cells behind.
     */
    @Test
    void jumpBehindAConditionalLetsExecutionPass() {
        label(2, LABEL_VALUE);
        conditional(3);
        withLabelOperand(8, "JMPI", LABEL_VALUE);
        place(30, new Molecule(Config.TYPE_DATA, 0));
        build(2, FORWARD);

        assertThat(reached(12, 2)).isTrue();
    }

    /**
     * A label between the conditional and the jump changes nothing: the machine's skip passes
     * everything that is no instruction, a label included, so a failed test still skips the jump
     * and execution runs on into the cells behind it. The jump is the only instruction of its
     * block here, and the conditional ends the block before.
     */
    @Test
    void conditionalSkipsAJumpAcrossALabel() {
        label(2, LABEL_VALUE);
        conditional(3);
        label(7, LABEL_VALUE + 1);
        withLabelOperand(8, "JMPI", LABEL_VALUE);
        place(30, new Molecule(Config.TYPE_DATA, 0));
        build(2, FORWARD);

        assertThat(reached(12, 2)).isTrue();
    }

    /**
     * A label between the jump and the cell is an entry of its own: a jump to it runs on into the
     * cell, whatever stands before the label.
     */
    @Test
    void labelBetweenJumpAndCellMakesItReached() {
        label(2, LABEL_VALUE);
        withLabelOperand(3, "JMPI", LABEL_VALUE);
        label(6, LABEL_VALUE + 1);
        place(30, new Molecule(Config.TYPE_DATA, 0));
        build(2, FORWARD);

        assertThat(reached(9, 2)).isTrue();
    }

    /**
     * A cell with no code before it on its line, only a shell molecule at the beginning of the
     * organism's extent: nothing comes from there.
     */
    @Test
    void cellWithNoCodeBeforeItIsNotReached() {
        label(12, LABEL_VALUE);
        ordinaryInstruction(13);
        place(2, new Molecule(Config.TYPE_STRUCTURE, 100));
        build(12, FORWARD);

        assertThat(reached(8, 2)).isFalse();
    }

    /**
     * A data molecule between the jump and the cell opens no instruction and is passed like an
     * empty cell, so the jump still decides.
     */
    @Test
    void moleculeThatOpensNoInstructionIsTransparent() {
        label(2, LABEL_VALUE);
        withLabelOperand(3, "JMPI", LABEL_VALUE);
        place(6, new Molecule(Config.TYPE_DATA, 5));
        place(30, new Molecule(Config.TYPE_DATA, 0));
        build(2, FORWARD);

        assertThat(reached(9, 2)).isFalse();
    }

    /**
     * A cell two walks of the frame read differently counts as reached. Here a LABEL molecule is
     * the operand of the jump: the walk from x=2 reads it as that operand, the walk that begins at
     * it as a label. Read as an operand alone it would lead back to the jump and the cells behind
     * it would seem out of reach — but a jump can land on that label and run on into them.
     */
    @Test
    void cellBehindACellWithTwoReadingsCountsAsReached() {
        label(2, LABEL_VALUE);
        opcode(3, "JMPI");
        label(4, LABEL_VALUE + 1);
        place(30, new Molecule(Config.TYPE_DATA, 0));
        build(2, FORWARD);

        assertThat(frame.slot(env.getProperties().toFlatIndex(new int[]{4, ROW})))
                .isEqualTo(GenomeFrame.Slot.AMBIGUOUS);
        assertThat(reached(8, 2)).isTrue();
    }

    /**
     * The walk goes against the direction vector, which for a vector towards falling coordinates
     * means towards rising ones.
     */
    @Test
    void walkFollowsABackwardDirectionVector() {
        // Execution runs from high x to low x: label, jump, then the empty cells at lower x.
        label(40, LABEL_VALUE);
        opcode(39, "JMPI");
        labelRef(38, LABEL_VALUE);
        place(10, new Molecule(Config.TYPE_DATA, 0));
        build(40, BACKWARD);

        boolean behindJump = flow.reachedByFallThrough(env, frame, new int[]{34, ROW}, DV_DIM, -1, 40 - 34);
        assertThat(behindJump).isFalse();
    }

    // ---- isJumpTarget ----

    /**
     * A label that a jump and a call address, and nothing else, is a jump target.
     */
    @Test
    void labelAddressedByJumpAndCallIsAJumpTarget() {
        label(2, LABEL_VALUE);
        withLabelOperand(3, "JMPI", LABEL_VALUE);
        withLabelOperand(6, "CALL", LABEL_VALUE);
        build(2, FORWARD);
        flow.collectReferences(env, frame, ORGANISM_ID, DV_DIM, 1);

        assertThat(flow.isJumpTarget(LABEL_VALUE, TOLERANCE)).isTrue();
    }

    /**
     * A label no reference comes within the tolerance of is no jump target: nothing would ever
     * arrive at code put in front of it.
     */
    @Test
    void labelNothingAddressesIsNoJumpTarget() {
        label(2, LABEL_VALUE);
        withLabelOperand(3, "JMPI", ~LABEL_VALUE & Config.VALUE_MASK);
        build(2, FORWARD);
        flow.collectReferences(env, frame, ORGANISM_ID, DV_DIM, 1);

        assertThat(flow.isJumpTarget(LABEL_VALUE, TOLERANCE)).isFalse();
    }

    /**
     * One reference in a location instruction is enough to make the label a place for the data
     * pointer, also when it matches the label only within the tolerance and a jump addresses the
     * label as well.
     */
    @Test
    void oneLocationInstructionMakesTheLabelAPlaceForData() {
        label(2, LABEL_VALUE);
        withLabelOperand(3, "JMPI", LABEL_VALUE);
        // One bit off: within the tolerance, so this reference addresses the label too
        withLabelOperand(6, "SKJI", LABEL_VALUE ^ 1);
        build(2, FORWARD);
        flow.collectReferences(env, frame, ORGANISM_ID, DV_DIM, 1);

        assertThat(flow.isJumpTarget(LABEL_VALUE, TOLERANCE)).isFalse();
    }

    /**
     * The opcode of a label operand is found over the operand cells before it: LRLI carries a
     * register operand in front of its label, and it is a location instruction.
     */
    @Test
    void labelOperandBehindARegisterOperandIsRecognized() {
        label(2, LABEL_VALUE);
        opcode(3, "LRLI");
        place(4, new Molecule(Config.TYPE_REGISTER, 0));
        labelRef(5, LABEL_VALUE);
        build(2, FORWARD);
        flow.collectReferences(env, frame, ORGANISM_ID, DV_DIM, 1);

        assertThat(flow.isJumpTarget(LABEL_VALUE, TOLERANCE)).isFalse();
    }

    /**
     * A label reference that stands in an immediate slot is a value a program computes with, not
     * what a jump matches against, so the label it addresses is no pure jump target.
     */
    @Test
    void referenceOutsideALabelSlotMakesTheLabelAPlaceForData() {
        label(2, LABEL_VALUE);
        withLabelOperand(3, "JMPI", LABEL_VALUE);
        opcode(6, "SETI");
        place(7, new Molecule(Config.TYPE_REGISTER, 0));
        labelRef(8, LABEL_VALUE);
        build(2, FORWARD);
        flow.collectReferences(env, frame, ORGANISM_ID, DV_DIM, 1);

        assertThat(flow.isJumpTarget(LABEL_VALUE, TOLERANCE)).isFalse();
    }

    // ---- drawsNoForeignReference ----

    /**
     * A new value for a label is refused if a reference that did not address the label before
     * would address it then, and accepted if it moves away from that reference.
     */
    @Test
    void newValueMustNotComeWithinReachOfAReferenceToAnotherLabel() {
        int other = LABEL_VALUE ^ 0b111;
        label(2, LABEL_VALUE);
        withLabelOperand(3, "JMPI", LABEL_VALUE);
        withLabelOperand(6, "JMPI", other);
        build(2, FORWARD);
        flow.collectReferences(env, frame, ORGANISM_ID, DV_DIM, 1);

        // Flipping bit 0 moves the label to two bits from the other reference, which did not reach it before
        assertThat(flow.drawsNoForeignReference(LABEL_VALUE ^ 1, LABEL_VALUE, TOLERANCE)).isFalse();
        // Flipping a bit the two values agree in moves it further away from that reference
        assertThat(flow.drawsNoForeignReference(LABEL_VALUE ^ (1 << 15), LABEL_VALUE, TOLERANCE)).isTrue();
    }

    // ---- endsOpen ----

    /** Asks whether the stretch from the label at 2 over {@code length} cells ends open. */
    private boolean open(int length) {
        return flow.endsOpen(env, frame, new int[]{2, ROW}, length, DV_DIM, 1);
    }

    /**
     * A stretch whose last instruction is an unconditional jump, with an ordinary instruction
     * before it, cannot be left at its end.
     */
    @Test
    void stretchEndingInAnUnconditionalJumpIsClosed() {
        label(2, LABEL_VALUE);
        ordinaryInstruction(3);
        withLabelOperand(6, "JMPI", LABEL_VALUE);
        build(2, FORWARD);

        assertThat(open(6)).isFalse();
    }

    /**
     * A stretch whose last instruction lets execution continue is open.
     */
    @Test
    void stretchEndingInAnOrdinaryInstructionIsOpen() {
        label(2, LABEL_VALUE);
        ordinaryInstruction(3);
        build(2, FORWARD);

        assertThat(open(4)).isTrue();
    }

    /**
     * A closing jump behind a conditional is skipped when the test fails, so the stretch is open.
     */
    @Test
    void jumpBehindAConditionalLeavesTheStretchOpen() {
        label(2, LABEL_VALUE);
        conditional(3);
        withLabelOperand(6, "JMPI", LABEL_VALUE);
        build(2, FORWARD);

        assertThat(open(6)).isTrue();
    }

    /**
     * A stretch that holds a label and nothing the machine executes is open: execution passes
     * straight through it.
     */
    @Test
    void stretchWithoutAnInstructionIsOpen() {
        label(2, LABEL_VALUE);
        place(10, new Molecule(Config.TYPE_DATA, 0));
        build(2, FORWARD);

        assertThat(open(3)).isTrue();
    }

    /**
     * A stretch that holds a cell with two readings is open, although its last instruction is an
     * unconditional jump: the LABEL molecule standing as that jump's operand is a label a jump can
     * land on, and execution runs on from there past the end of the stretch.
     */
    @Test
    void stretchHoldingACellWithTwoReadingsIsOpen() {
        label(2, LABEL_VALUE);
        opcode(3, "JMPI");
        label(4, LABEL_VALUE + 1);
        build(2, FORWARD);

        assertThat(frame.slot(env.getProperties().toFlatIndex(new int[]{4, ROW})))
                .isEqualTo(GenomeFrame.Slot.AMBIGUOUS);
        assertThat(open(3)).isTrue();
    }

    /**
     * Only a conditional inside the stretch counts: one that stands before the stretch's first
     * cell is not part of what a copy of the stretch would carry.
     */
    @Test
    void conditionalBeforeTheStretchDoesNotOpenIt() {
        label(0, LABEL_VALUE + 1);
        // The conditional stands before the stretch, which begins at the label at 5
        opcode(1, "IFI");
        place(2, new Molecule(Config.TYPE_REGISTER, 0));
        place(3, new Molecule(Config.TYPE_DATA, 0));
        label(5, LABEL_VALUE);
        opcode(6, "RET");
        build(0, FORWARD);

        assertThat(flow.endsOpen(env, frame, new int[]{5, ROW}, 2, DV_DIM, 1)).isFalse();
    }
}
