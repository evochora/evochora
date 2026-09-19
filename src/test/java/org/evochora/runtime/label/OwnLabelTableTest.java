package org.evochora.runtime.label;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for OwnLabelTable: what it answers for an owner and a label value, through additions,
 * removals, duplicated values and growth.
 */
@Tag("unit")
class OwnLabelTableTest {

    private static final int OWNER = 3;
    private static final int VALUE = 0xABCDE;

    private final OwnLabelTable table = new OwnLabelTable();

    @Test
    void answersNoneForAnUnknownOwnerOrValue() {
        table.add(OWNER, VALUE, 100);

        assertThat(table.find(OWNER, VALUE + 1)).isEqualTo(OwnLabelTable.NONE);
        assertThat(table.find(OWNER + 1, VALUE)).isEqualTo(OwnLabelTable.NONE);
    }

    @Test
    void answersTheFlatIndexOfTheOnlyLabelWithAValue() {
        table.add(OWNER, VALUE, 100);

        assertThat(table.find(OWNER, VALUE)).isEqualTo(100);
    }

    @Test
    void keepsTheSameValueOfDifferentOwnersApart() {
        table.add(OWNER, VALUE, 100);
        table.add(OWNER + 1, VALUE, 200);

        assertThat(table.find(OWNER, VALUE)).isEqualTo(100);
        assertThat(table.find(OWNER + 1, VALUE)).isEqualTo(200);
    }

    @Test
    void answersSeveralForADuplicatedValue_andTheRemainingLabelOnceTheOtherIsGone() {
        LabelList ownLabels = new LabelList();
        ownLabels.put(100, VALUE);
        ownLabels.put(200, VALUE);
        table.add(OWNER, VALUE, 100);
        table.add(OWNER, VALUE, 200);
        assertThat(table.find(OWNER, VALUE)).isEqualTo(OwnLabelTable.SEVERAL);

        ownLabels.remove(100);
        table.remove(OWNER, VALUE, ownLabels);

        assertThat(table.find(OWNER, VALUE)).isEqualTo(200);
    }

    @Test
    void staysAtSeveralWhileMoreThanOneDuplicateIsLeft() {
        LabelList ownLabels = new LabelList();
        for (int flatIndex : new int[]{100, 200, 300}) {
            ownLabels.put(flatIndex, VALUE);
            table.add(OWNER, VALUE, flatIndex);
        }

        ownLabels.remove(200);
        table.remove(OWNER, VALUE, ownLabels);

        assertThat(table.find(OWNER, VALUE)).isEqualTo(OwnLabelTable.SEVERAL);
    }

    @Test
    void forgetsAValueWithItsLastLabel() {
        table.add(OWNER, VALUE, 100);

        table.remove(OWNER, VALUE, new LabelList());

        assertThat(table.find(OWNER, VALUE)).isEqualTo(OwnLabelTable.NONE);
        assertThat(table.size()).isZero();
    }

    @Test
    void ignoresTheRemovalOfAnEntryItDoesNotHold() {
        table.add(OWNER, VALUE, 100);

        table.remove(OWNER, VALUE + 1, new LabelList());

        assertThat(table.find(OWNER, VALUE)).isEqualTo(100);
        assertThat(table.size()).isEqualTo(1);
    }

    @Test
    void keepsEveryEntryReachableThroughGrowthAndRemovals() {
        // Far more entries than the initial capacity, so that the table grows several times and
        // probe sequences of colliding keys form; then every second entry is removed again
        int owners = 300;
        int valuesPerOwner = 40;
        for (int owner = 1; owner <= owners; owner++) {
            for (int v = 0; v < valuesPerOwner; v++) {
                table.add(owner, value(owner, v), flatIndex(owner, v));
            }
        }
        assertThat(table.size()).isEqualTo(owners * valuesPerOwner);

        for (int owner = 1; owner <= owners; owner++) {
            for (int v = 0; v < valuesPerOwner; v += 2) {
                table.remove(owner, value(owner, v), new LabelList());
            }
        }

        assertThat(table.size()).isEqualTo(owners * valuesPerOwner / 2);
        for (int owner = 1; owner <= owners; owner++) {
            for (int v = 0; v < valuesPerOwner; v++) {
                int expected = v % 2 == 0 ? OwnLabelTable.NONE : flatIndex(owner, v);
                assertThat(table.find(owner, value(owner, v))).isEqualTo(expected);
            }
        }
    }

    private static int value(int owner, int v) {
        return (owner * 7919 + v * 104729) & 0xFFFFF;
    }

    private static int flatIndex(int owner, int v) {
        return owner * 1000 + v;
    }
}
