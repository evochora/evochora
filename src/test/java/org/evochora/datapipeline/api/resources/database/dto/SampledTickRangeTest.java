package org.evochora.datapipeline.api.resources.database.dto;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A range keeps its own contract: it runs forward, has a step, and ends on its grid.
 */
@Tag("unit")
class SampledTickRangeTest {

    @Test
    void aRangeEndingOnItsGridIsAccepted() {
        assertThatCode(() -> new SampledTickRange(0, 100, 10)).doesNotThrowAnyException();
        assertThatCode(() -> new SampledTickRange(150_000, 150_000, 1)).doesNotThrowAnyException();
    }

    @Test
    void aLastTickOffTheGridIsRefused() {
        assertThatThrownBy(() -> new SampledTickRange(0, 1, 2))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("whole number of steps");
    }

    @Test
    void aBackwardsRangeAndAStepBelowOneAreRefused() {
        assertThatThrownBy(() -> new SampledTickRange(10, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SampledTickRange(0, 10, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
