package org.evochora.runtime.label;

import org.evochora.runtime.Config;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.OrganismRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for LabelIndex: which changes of a cell it reports to the matching strategy, and
 * that a label is a jump target only while its marker is 0.
 */
@Tag("unit")
class LabelIndexTest {

    private static final int OWNER = 1;
    private static final int LABEL_VALUE = 12345;
    private static final int UNMARKED = Config.TYPE_LABEL | LABEL_VALUE;

    private HammingLabelMatchingStrategy strategy;
    private LabelIndex labelIndex;
    private Environment environment;
    private int[] callerCoords;
    private OrganismRandom random;

    @BeforeEach
    void setUp() {
        strategy = new HammingLabelMatchingStrategy();
        labelIndex = new LabelIndex(strategy);
        environment = new Environment(new EnvironmentProperties(new int[]{64, 64}, true));
        strategy.initialize(environment.getProperties());
        callerCoords = new int[]{0, 0};
        // The random source of the organism performing the lookups, positioned at a fixed tick
        random = new OrganismRandom(OWNER);
        random.beginTick(42L);
    }

    private int targetFor(int searchValue, int organismId) {
        return labelIndex.findTarget(searchValue, organismId, callerCoords, random);
    }

    /** Packs a LABEL molecule with a marker, as a write with a non-zero marker register stores it. */
    private static int markedLabel(int labelValue, int marker) {
        return (marker << Config.MARKER_SHIFT) | Config.TYPE_LABEL | labelValue;
    }

    @Test
    void findsNothingWithoutLabels() {
        assertThat(targetFor(LABEL_VALUE, OWNER)).isEqualTo(-1);
    }

    @Test
    void reportsAWrittenLabelUnderTheCellsOwner() {
        labelIndex.onMoleculeSet(100, 0, 0, UNMARKED, OWNER);

        assertThat(strategy.ownerOf(LABEL_VALUE, 100)).isEqualTo(OWNER);
        assertThat(targetFor(LABEL_VALUE, OWNER)).isEqualTo(100);
    }

    @Test
    void reportsAnOverwrittenLabelAsGone() {
        labelIndex.onMoleculeSet(100, 0, 0, UNMARKED, OWNER);

        labelIndex.onMoleculeSet(100, UNMARKED, OWNER, 0, OWNER);

        assertThat(strategy.ownerOf(LABEL_VALUE, 100)).isEqualTo(-1);
        assertThat(targetFor(LABEL_VALUE, OWNER)).isEqualTo(-1);
    }

    @Test
    void reportsAReplacedLabelUnderItsNewValue() {
        int otherValue = 54321;
        labelIndex.onMoleculeSet(100, 0, 0, UNMARKED, OWNER);

        labelIndex.onMoleculeSet(100, UNMARKED, OWNER, Config.TYPE_LABEL | otherValue, OWNER);

        assertThat(strategy.ownerOf(LABEL_VALUE, 100)).isEqualTo(-1);
        assertThat(strategy.ownerOf(otherValue, 100)).isEqualTo(OWNER);
    }

    @Test
    void aWriteThatAlsoChangesTheOwnerRemovesTheLabelUnderItsOldOwner() {
        int newOwner = 2;
        labelIndex.onMoleculeSet(100, 0, 0, UNMARKED, OWNER);

        labelIndex.onMoleculeSet(100, UNMARKED, OWNER, UNMARKED, newOwner);

        assertThat(strategy.ownerOf(LABEL_VALUE, 100)).isEqualTo(newOwner);
        assertThat(targetFor(LABEL_VALUE, newOwner)).isEqualTo(100);
    }

    @Test
    void reportsAnOwnerChange() {
        int newOwner = 2;
        labelIndex.onMoleculeSet(100, 0, 0, UNMARKED, OWNER);

        labelIndex.onOwnerChange(100, UNMARKED, OWNER, newOwner);

        assertThat(strategy.ownerOf(LABEL_VALUE, 100)).isEqualTo(newOwner);
    }

    @Test
    void ignoresAMoleculeThatIsNoLabel() {
        labelIndex.onMoleculeSet(100, 0, 0, Config.TYPE_DATA | LABEL_VALUE, OWNER);

        assertThat(strategy.ownerOf(LABEL_VALUE, 100)).isEqualTo(-1);
    }

    @Test
    void markedLabelIsNoTarget_notEvenForItsWriter() {
        labelIndex.onMoleculeSet(100, 0, 0, markedLabel(LABEL_VALUE, 3), OWNER);

        assertThat(strategy.ownerOf(LABEL_VALUE, 100)).isEqualTo(-1);
        assertThat(targetFor(LABEL_VALUE, OWNER)).isEqualTo(-1);
        assertThat(targetFor(LABEL_VALUE, 2)).isEqualTo(-1);
    }

    @Test
    void anOwnerChangeOfAMarkedLabelIsNotReported() {
        int marked = markedLabel(LABEL_VALUE, 3);
        labelIndex.onMoleculeSet(100, 0, 0, marked, OWNER);

        labelIndex.onOwnerChange(100, marked, OWNER, 2);

        assertThat(strategy.ownerOf(LABEL_VALUE, 100)).isEqualTo(-1);
    }

    @Test
    void releasedMarkedLabel_becomesATargetOfItsNewOwner() {
        int child = 2;
        int marked = markedLabel(LABEL_VALUE, 3);
        labelIndex.onMoleculeSet(100, 0, 0, marked, OWNER);

        labelIndex.onCellReleased(100, marked, OWNER, child);

        assertThat(strategy.ownerOf(LABEL_VALUE, 100)).isEqualTo(child);
    }

    @Test
    void releasedUnmarkedLabel_staysATargetAndTakesTheNewOwner() {
        labelIndex.onMoleculeSet(100, 0, 0, UNMARKED, OWNER);

        labelIndex.onCellReleased(100, UNMARKED, OWNER, 0);

        assertThat(strategy.ownerOf(LABEL_VALUE, 100)).isZero();
        assertThat(targetFor(LABEL_VALUE, OWNER))
                .as("the former owner has no own label any more and reaches the unowned one as a foreign label")
                .isEqualTo(100);
    }

    @Test
    void overwritingOrClearingAMarkedLabel_leavesTheIndexConsistent() {
        int marked = markedLabel(LABEL_VALUE, 3);
        // An unmarked label of the same value elsewhere: it must survive whatever happens to the marked one
        labelIndex.onMoleculeSet(200, 0, 0, UNMARKED, OWNER);
        labelIndex.onMoleculeSet(100, 0, 0, marked, OWNER);

        // Overwrite the marked label by an unmarked one, then clear that cell
        labelIndex.onMoleculeSet(100, marked, OWNER, UNMARKED, OWNER);
        assertThat(strategy.ownerOf(LABEL_VALUE, 100)).isEqualTo(OWNER);
        assertThat(strategy.ownerOf(LABEL_VALUE, 200)).isEqualTo(OWNER);
        labelIndex.onMoleculeSet(100, UNMARKED, OWNER, 0, 0);

        // A marked label that is cleared was never reported: nothing to remove, nothing removed
        labelIndex.onMoleculeSet(300, 0, 0, marked, OWNER);
        labelIndex.onMoleculeSet(300, marked, OWNER, 0, 0);

        assertThat(strategy.ownerOf(LABEL_VALUE, 100)).isEqualTo(-1);
        assertThat(strategy.ownerOf(LABEL_VALUE, 300)).isEqualTo(-1);
        assertThat(strategy.ownerOf(LABEL_VALUE, 200)).isEqualTo(OWNER);
    }
}
