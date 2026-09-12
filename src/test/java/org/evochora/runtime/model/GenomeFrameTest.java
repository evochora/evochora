package org.evochora.runtime.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.GenomeFrame.Slot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GenomeFrame}.
 * <p>
 * Every case builds its grid by hand, places the molecules with their ownership and reads the
 * result through the public lookup by flat index.
 */
@Tag("unit")
class GenomeFrameTest {

    private static final int ORGANISM_ID = 7;
    private static final int OTHER_ORGANISM_ID = 8;
    private static final int ROW = 4;
    private static final int[] FORWARD = new int[]{1, 0};
    private static final int[] BACKWARD = new int[]{-1, 0};

    private Environment env;
    private GenomeFrame frame;

    @BeforeAll
    static void initInstructionSet() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        env = new Environment(new int[]{32, 32}, false);
        frame = new GenomeFrame();
    }

    /** Places a molecule owned by the organism under test. */
    private void place(int x, Molecule molecule) {
        env.setMolecule(molecule, ORGANISM_ID, new int[]{x, ROW});
    }

    /** Places the opcode cell of the named instruction. */
    private void opcode(int x, String name) {
        place(x, new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName(name)));
    }

    /** Places a cell that carries a register id. */
    private void register(int x, int id) {
        place(x, new Molecule(Config.TYPE_REGISTER, id));
    }

    /** Places a cell that carries a number. */
    private void data(int x, int value) {
        place(x, new Molecule(Config.TYPE_DATA, value));
    }

    /** The flat index of a cell in the row the tests use. */
    private int flat(int x) {
        return env.getProperties().toFlatIndex(new int[]{x, ROW});
    }

    /** The role of a cell in the row the tests use. */
    private Slot slotAt(int x) {
        return frame.slot(flat(x));
    }

    /** The vector component of a cell in the row the tests use. */
    private int componentAt(int x) {
        return frame.componentIndex(flat(x));
    }

    /** Builds the frame for the organism under test, entering at the given position. */
    private void build(int initialX, int[] dv) {
        frame.build(env, ORGANISM_ID, new int[]{initialX, ROW}, dv);
    }

    /** An opcode value no instruction is registered under. */
    private static int unregisteredOpcodeValue() {
        // 8192 is one past the highest id the opcode layout can express: five family bits and
        // eight index bits.
        for (int value = 1; value < 8192; value++) {
            if (Instruction.getPlannerById(value) == null) {
                return value;
            }
        }
        throw new IllegalStateException("Every opcode value the layout can express is registered");
    }

    /**
     * A label followed by instructions: every opcode cell is an instruction and every argument
     * cell carries the kind of its slot.
     */
    @Test
    void straightSequenceAfterLabelGetsSlotPerCell() {
        place(2, new Molecule(Config.TYPE_LABEL, 1234));
        opcode(3, "SETI");
        register(4, 0);
        data(5, 42);
        opcode(6, "JMPI");
        place(7, new Molecule(Config.TYPE_LABELREF, 1234));
        opcode(8, "PUSH");
        register(9, 1);

        build(2, FORWARD);

        assertThat(slotAt(2)).isEqualTo(Slot.NONE);
        assertThat(slotAt(3)).isEqualTo(Slot.INSTRUCTION);
        assertThat(slotAt(4)).isEqualTo(Slot.REGISTER);
        assertThat(slotAt(5)).isEqualTo(Slot.SCALAR);
        assertThat(slotAt(6)).isEqualTo(Slot.INSTRUCTION);
        assertThat(slotAt(7)).isEqualTo(Slot.LABEL);
        assertThat(slotAt(8)).isEqualTo(Slot.INSTRUCTION);
        assertThat(slotAt(9)).isEqualTo(Slot.REGISTER);
    }

    /** A vector operand occupies one cell per world dimension, each with its component index. */
    @Test
    void vectorOperandCarriesItsComponentIndices() {
        place(2, new Molecule(Config.TYPE_LABEL, 1234));
        opcode(3, "SEKI");
        data(4, 1);
        data(5, 0);

        build(2, FORWARD);

        assertThat(slotAt(3)).isEqualTo(Slot.INSTRUCTION);
        assertThat(slotAt(4)).isEqualTo(Slot.VECTOR);
        assertThat(slotAt(5)).isEqualTo(Slot.VECTOR);
        assertThat(componentAt(4)).isEqualTo(0);
        assertThat(componentAt(5)).isEqualTo(1);
        assertThat(componentAt(3)).isEqualTo(-1);
    }

    /**
     * A CODE molecule of value zero in an immediate slot is that instruction's literal, not a gap,
     * and the instruction behind it is read in the same frame.
     */
    @Test
    void codeZeroInAnImmediateSlotIsALiteral() {
        place(2, new Molecule(Config.TYPE_LABEL, 1234));
        opcode(3, "SETI");
        register(4, 0);
        place(5, new Molecule(Config.TYPE_CODE, 0));
        opcode(6, "SEKI");
        data(7, 1);
        data(8, 0);

        build(2, FORWARD);

        assertThat(slotAt(5)).isEqualTo(Slot.SCALAR);
        assertThat(slotAt(6)).isEqualTo(Slot.INSTRUCTION);
        assertThat(slotAt(7)).isEqualTo(Slot.VECTOR);
        assertThat(componentAt(7)).isEqualTo(0);
        assertThat(slotAt(8)).isEqualTo(Slot.VECTOR);
        assertThat(componentAt(8)).isEqualTo(1);
    }

    /**
     * A label inside another instruction's operand list is a second frame start, and the cells the
     * two walks read differently have no single role. Here the containing instruction lies before
     * the inner label, so the walk that contains it runs first.
     */
    @Test
    void labelInsideAnOperandListMakesItsCellsAmbiguous() {
        place(2, new Molecule(Config.TYPE_LABEL, 1234));
        opcode(3, "SETI");
        place(4, new Molecule(Config.TYPE_LABEL, 5678));
        data(5, 42);

        build(2, FORWARD);

        assertThat(slotAt(3)).isEqualTo(Slot.INSTRUCTION);
        assertThat(slotAt(4)).isEqualTo(Slot.AMBIGUOUS);
        assertThat(slotAt(5)).isEqualTo(Slot.AMBIGUOUS);

        Slot[] first = new Slot[]{slotAt(2), slotAt(3), slotAt(4), slotAt(5)};
        build(2, FORWARD);
        assertThat(new Slot[]{slotAt(2), slotAt(3), slotAt(4), slotAt(5)}).isEqualTo(first);
    }

    /**
     * The same conflict with the walks in the other order: reading towards falling coordinates,
     * the inner label lies before the instruction that contains it.
     */
    @Test
    void labelInsideAnOperandListIsAmbiguousInTheOtherWalkOrderToo() {
        place(10, new Molecule(Config.TYPE_LABEL, 1234));
        opcode(9, "SETI");
        place(8, new Molecule(Config.TYPE_LABEL, 5678));
        data(7, 42);

        build(10, BACKWARD);

        assertThat(slotAt(10)).isEqualTo(Slot.NONE);
        assertThat(slotAt(9)).isEqualTo(Slot.INSTRUCTION);
        assertThat(slotAt(8)).isEqualTo(Slot.AMBIGUOUS);
        assertThat(slotAt(7)).isEqualTo(Slot.AMBIGUOUS);
    }

    /** A cell no walk reaches has no role, whether the organism owns it or not. */
    @Test
    void cellsNoWalkReachesHaveNoRole() {
        place(2, new Molecule(Config.TYPE_LABEL, 1234));
        opcode(3, "SETI");
        register(4, 0);
        data(5, 42);
        // A second line the organism owns, with no label on it and no entry at its cells
        env.setMolecule(new Molecule(Config.TYPE_DATA, 7), ORGANISM_ID, new int[]{3, ROW + 2});
        env.setMolecule(new Molecule(Config.TYPE_REGISTER, 0), ORGANISM_ID, new int[]{4, ROW + 2});
        // A cell of another organism, beyond this one's extent
        env.setMolecule(new Molecule(Config.TYPE_DATA, 9), OTHER_ORGANISM_ID, new int[]{20, ROW});

        build(2, FORWARD);

        assertThat(frame.slot(env.getProperties().toFlatIndex(new int[]{3, ROW + 2}))).isEqualTo(Slot.NONE);
        assertThat(frame.slot(env.getProperties().toFlatIndex(new int[]{4, ROW + 2}))).isEqualTo(Slot.NONE);
        assertThat(slotAt(20)).isEqualTo(Slot.NONE);
    }

    /** A cell another organism owns is walked over like any other cell of the line. */
    @Test
    void foreignCellInsideTheLineIsWalked() {
        place(2, new Molecule(Config.TYPE_LABEL, 1234));
        opcode(3, "SETI");
        register(4, 0);
        env.setMolecule(new Molecule(Config.TYPE_DATA, 42), OTHER_ORGANISM_ID, new int[]{5, ROW});
        opcode(6, "PUSH");
        register(7, 1);

        build(2, FORWARD);

        assertThat(slotAt(4)).isEqualTo(Slot.REGISTER);
        assertThat(slotAt(5)).isEqualTo(Slot.SCALAR);
        assertThat(slotAt(6)).isEqualTo(Slot.INSTRUCTION);
        assertThat(slotAt(7)).isEqualTo(Slot.REGISTER);
    }

    /** An opcode value that is registered nowhere occupies one cell and opens no operand slots. */
    @Test
    void unregisteredOpcodeOccupiesOneCell() {
        place(2, new Molecule(Config.TYPE_LABEL, 1234));
        place(3, new Molecule(Config.TYPE_CODE, unregisteredOpcodeValue()));
        opcode(4, "SETI");
        register(5, 0);
        data(6, 42);

        build(2, FORWARD);

        assertThat(slotAt(3)).isEqualTo(Slot.NONE);
        assertThat(slotAt(4)).isEqualTo(Slot.INSTRUCTION);
        assertThat(slotAt(5)).isEqualTo(Slot.REGISTER);
        assertThat(slotAt(6)).isEqualTo(Slot.SCALAR);
    }

    /**
     * In a toroidal world a body across the world edge is one line, and an instruction that runs
     * across the edge is read across it; what reaches beyond the end of the line is cut.
     */
    @Test
    void walkFollowsTheArcAcrossTheWorldEdge() {
        env = new Environment(new int[]{32, 32}, true);
        placeBodyAcrossTheEdge();

        build(30, FORWARD);

        assertThat(slotAt(30)).isEqualTo(Slot.NONE);
        assertThat(slotAt(31)).isEqualTo(Slot.INSTRUCTION);
        assertThat(slotAt(0)).isEqualTo(Slot.REGISTER);
        assertThat(slotAt(1)).isEqualTo(Slot.SCALAR);
        assertThat(slotAt(2)).isEqualTo(Slot.INSTRUCTION);
        assertThat(slotAt(3)).isEqualTo(Slot.VECTOR);
        assertThat(componentAt(3)).isEqualTo(0);
        // The second component of the vector lies beyond the end of the line and is cut off
        assertThat(slotAt(4)).isEqualTo(Slot.NONE);
    }

    /** The same body in a bounded world: the line ends at the world edge, and nothing wraps. */
    @Test
    void walkStopsAtTheWorldEdgeWhereTheWorldIsBounded() {
        placeBodyAcrossTheEdge();

        build(30, FORWARD);

        assertThat(slotAt(30)).isEqualTo(Slot.NONE);
        assertThat(slotAt(31)).isEqualTo(Slot.INSTRUCTION);
        assertThat(slotAt(0)).isEqualTo(Slot.NONE);
        assertThat(slotAt(1)).isEqualTo(Slot.NONE);
        assertThat(slotAt(2)).isEqualTo(Slot.NONE);
        assertThat(slotAt(3)).isEqualTo(Slot.NONE);
    }

    /** A body whose cells sit on both sides of the world edge, entered at its label. */
    private void placeBodyAcrossTheEdge() {
        place(30, new Molecule(Config.TYPE_LABEL, 1234));
        opcode(31, "SETI");
        register(0, 0);
        data(1, 42);
        opcode(2, "SEKI");
        data(3, 1);
    }

    /** The same grid gives the same roles every time it is read. */
    @Test
    void theSameGridGivesTheSameFrameTwice() {
        place(2, new Molecule(Config.TYPE_LABEL, 1234));
        opcode(3, "SETI");
        register(4, 0);
        data(5, 42);
        opcode(6, "SEKI");
        data(7, 1);
        data(8, 0);
        place(9, new Molecule(Config.TYPE_LABEL, 5678));
        opcode(10, "JMPI");
        place(11, new Molecule(Config.TYPE_LABELREF, 1234));

        build(2, FORWARD);
        Slot[] slots = new Slot[32];
        int[] components = new int[32];
        for (int x = 0; x < 32; x++) {
            slots[x] = slotAt(x);
            components[x] = componentAt(x);
        }

        build(2, FORWARD);

        for (int x = 0; x < 32; x++) {
            assertThat(slotAt(x)).as("slot at x=" + x).isEqualTo(slots[x]);
            assertThat(componentAt(x)).as("component at x=" + x).isEqualTo(components[x]);
        }
    }

    /** Without a label before it, the organism's initial position is what opens the frame. */
    @Test
    void theInitialPositionOpensAFrame() {
        opcode(3, "SETI");
        register(4, 0);
        data(5, 42);
        opcode(6, "PUSH");
        register(7, 1);

        build(3, FORWARD);

        assertThat(slotAt(3)).isEqualTo(Slot.INSTRUCTION);
        assertThat(slotAt(4)).isEqualTo(Slot.REGISTER);
        assertThat(slotAt(5)).isEqualTo(Slot.SCALAR);
        assertThat(slotAt(6)).isEqualTo(Slot.INSTRUCTION);
        assertThat(slotAt(7)).isEqualTo(Slot.REGISTER);
    }
}
