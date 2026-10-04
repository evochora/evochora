package org.evochora.compiler.api;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SourceInfo}.
 */
@Tag("unit")
class SourceInfoTest {

    @Test
    void withoutExpansionKeepsEverythingButTheInstance() {
        SourceInfo at = new SourceInfo("lib.evo", 3, 7, "LIB", 4);

        assertThat(at.withoutExpansion()).isEqualTo(new SourceInfo("lib.evo", 3, 7, "LIB", 0));
    }

    @Test
    void withoutExpansionReturnsAPositionOfTheTextAsWrittenUnchanged() {
        SourceInfo at = new SourceInfo("lib.evo", 3, 7, "LIB", 0);

        assertThat(at.withoutExpansion()).isSameAs(at);
    }
}
