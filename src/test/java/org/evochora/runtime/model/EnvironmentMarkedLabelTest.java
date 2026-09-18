package org.evochora.runtime.model;

import org.evochora.runtime.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A label written with a non-zero marker belongs to a body that is still being built. These tests
 * follow such a label through the environment's own mutators: it is no jump target while it is
 * marked, and becomes one when a fork or its owner's death resets the marker.
 */
@Tag("unit")
class EnvironmentMarkedLabelTest {

    private static final int LABEL_VALUE = 0b1011_0110_0101_1001;
    private static final int MARKER = 3;
    private static final int PARENT = 1;
    private static final int CHILD = 2;
    private static final int STRANGER = 9;

    private Environment environment;
    private int[] labelCoord;
    private int labelFlatIndex;
    private int[] callerCoords;
    private OrganismRandom random;

    @BeforeEach
    void setUp() {
        environment = new Environment(new EnvironmentProperties(new int[]{64, 64}, true));
        labelCoord = new int[]{10, 10};
        labelFlatIndex = environment.getProperties().toFlatIndex(labelCoord);
        callerCoords = new int[]{8, 10};
        random = new OrganismRandom(PARENT);
        random.beginTick(42L);
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, LABEL_VALUE, MARKER), PARENT, labelCoord);
    }

    private int targetFor(int organismId) {
        return environment.getLabelIndex().findTarget(LABEL_VALUE, organismId, callerCoords, environment, random);
    }

    @Test
    void markedLabelIsNoTargetBeforeTheFork_notEvenForItsWriter() {
        assertThat(targetFor(PARENT)).isEqualTo(-1);
        assertThat(targetFor(STRANGER)).isEqualTo(-1);
    }

    @Test
    void forkMakesTheLabelTheChildsOwn() {
        environment.transferOwnership(PARENT, CHILD, MARKER);

        assertThat(targetFor(CHILD)).isEqualTo(labelFlatIndex);
        assertThat(environment.getLabelIndex().getCandidates(LABEL_VALUE))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.owner()).isEqualTo(CHILD);
                    assertThat(entry.isForeign(CHILD)).isFalse();
                    assertThat(entry.isForeign(PARENT)).isTrue();
                });
    }

    @Test
    void deathOfTheOwnerLeavesTheLabelUnowned() {
        environment.clearOwnershipFor(PARENT);

        assertThat(environment.getLabelIndex().getCandidates(LABEL_VALUE))
                .singleElement()
                .satisfies(entry -> assertThat(entry.owner()).isZero());
        assertThat(targetFor(STRANGER)).isEqualTo(labelFlatIndex);
    }

    @Test
    void clearingTheMarkedCellsOfAnAbortedBuildLeavesNothingBehind() {
        environment.clearMarkersFor(PARENT, MARKER);

        assertThat(environment.getLabelIndex().getCandidates(LABEL_VALUE)).isEmpty();
        assertThat(targetFor(PARENT)).isEqualTo(-1);
    }

    @Test
    void overwritingTheMarkedLabelWithAnUnmarkedOneMakesItATarget() {
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, LABEL_VALUE, 0), PARENT, labelCoord);

        assertThat(targetFor(PARENT)).isEqualTo(labelFlatIndex);
    }
}
