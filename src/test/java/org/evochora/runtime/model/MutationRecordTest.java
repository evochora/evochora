package org.evochora.runtime.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MutationRecord}.
 * <p>
 * The record is what a plugin hands over about a write, so the tests pin that it keeps the write
 * order, that it cannot be changed through the arrays a caller passed in or reads back, and that
 * the builder serves one plugin over many births.
 */
@Tag("unit")
class MutationRecordTest {

    @Test
    void keepsWhatThePluginReported() {
        MutationRecord record = new MutationRecord(
            "org.example.Plugin", "substitution",
            new int[] {7, 8}, new int[] {100, 101}, new int[] {200, 201},
            new long[] {42L}, new int[] {1, 0});

        assertThat(record.pluginClass()).isEqualTo("org.example.Plugin");
        assertThat(record.kind()).isEqualTo("substitution");
        assertThat(record.cells()).containsExactly(7, 8);
        assertThat(record.oldValues()).containsExactly(100, 101);
        assertThat(record.newValues()).containsExactly(200, 201);
        assertThat(record.params()).containsExactly(42L);
        assertThat(record.dv()).containsExactly(1, 0);
        assertThat(record.cellCount()).isEqualTo(2);
    }

    @Test
    void theCallersArraysCannotChangeTheRecordAfterwards() {
        int[] cells = {7, 8};
        int[] dv = {1, 0};
        MutationRecord record = new MutationRecord("p", "k", cells, new int[] {0, 0},
            new int[] {1, 1}, new long[0], dv);

        cells[0] = 999;
        dv[0] = -1;

        assertThat(record.cells()).containsExactly(7, 8);
        assertThat(record.dv()).containsExactly(1, 0);
    }

    @Test
    void writingIntoWhatWasReadBackDoesNotChangeTheRecord() {
        MutationRecord record = new MutationRecord("p", "k", new int[] {7}, new int[] {0},
            new int[] {1}, new long[] {5L}, new int[] {0, 1});

        record.cells()[0] = 999;
        record.oldValues()[0] = 999;
        record.newValues()[0] = 999;
        record.params()[0] = 999L;
        record.dv()[0] = 999;

        assertThat(record.cells()).containsExactly(7);
        assertThat(record.oldValues()).containsExactly(0);
        assertThat(record.newValues()).containsExactly(1);
        assertThat(record.params()).containsExactly(5L);
        assertThat(record.dv()).containsExactly(0, 1);
    }

    @Test
    void anEventWithoutCellsIsARecordAllTheSame() {
        MutationRecord record = new MutationRecord("p", "label-rewrite", new int[0], new int[0],
            new int[0], new long[] {0x2A}, new int[] {1, 0});

        assertThat(record.cellCount()).isZero();
        assertThat(record.cells()).isEmpty();
        assertThat(record.params()).containsExactly(0x2A);
    }

    @Test
    void perCellArraysOfDifferentLengthAreRejected() {
        assertThatThrownBy(() -> new MutationRecord("p", "k", new int[] {1, 2}, new int[] {0},
            new int[] {1, 2}, new long[0], new int[] {1, 0}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("same length");
    }

    @Test
    void theBuilderKeepsTheCellsInWriteOrder() {
        // The walk runs backwards along the scan line, and that order is the observation
        MutationRecord record = new MutationRecord.Builder()
            .start("p", "duplication", new int[] {-1, 0})
            .cell(30, 0, 11)
            .cell(29, 0, 12)
            .cell(28, 0, 13)
            .param(64L)
            .build();

        assertThat(record.cells()).containsExactly(30, 29, 28);
        assertThat(record.newValues()).containsExactly(11, 12, 13);
        assertThat(record.params()).containsExactly(64L);
        assertThat(record.dv()).containsExactly(-1, 0);
    }

    @Test
    void aStartedBuilderCarriesNothingOverFromTheRecordBefore() {
        MutationRecord.Builder builder = new MutationRecord.Builder();
        builder.start("p", "first", new int[] {1, 0}).cell(1, 0, 1).param(9L).build();

        MutationRecord second = builder.start("p", "second", new int[] {0, 1}).cell(2, 3, 4).build();

        assertThat(second.kind()).isEqualTo("second");
        assertThat(second.cells()).containsExactly(2);
        assertThat(second.oldValues()).containsExactly(3);
        assertThat(second.newValues()).containsExactly(4);
        assertThat(second.params()).isEmpty();
        assertThat(second.dv()).containsExactly(0, 1);
    }

    @Test
    void aBuilderThatWasNeverStartedRefusesToBuild() {
        assertThatThrownBy(() -> new MutationRecord.Builder().build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("start");
    }

    @Test
    void twoRecordsOfTheSameWriteAreEqual() {
        MutationRecord one = new MutationRecord("p", "k", new int[] {7}, new int[] {0},
            new int[] {1}, new long[] {5L}, new int[] {1, 0});
        MutationRecord other = new MutationRecord("p", "k", new int[] {7}, new int[] {0},
            new int[] {1}, new long[] {5L}, new int[] {1, 0});

        assertThat(one).isEqualTo(other).hasSameHashCodeAs(other);
        assertThat(one).isNotEqualTo(new MutationRecord("p", "k", new int[] {8}, new int[] {0},
            new int[] {1}, new long[] {5L}, new int[] {1, 0}));
    }
}
