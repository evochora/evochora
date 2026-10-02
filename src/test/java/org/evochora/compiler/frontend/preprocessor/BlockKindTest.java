package org.evochora.compiler.frontend.preprocessor;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link BlockKind}: the words it accepts, and how it tells its words apart.
 */
@Tag("unit")
class BlockKindTest {

    @Test
    void aKindWithoutAnOpenerIsRejected() {
        assertThatThrownBy(() -> new BlockKind(Set.of(), ".ENDX", Set.of(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one opener");
    }

    @Test
    void aWordUsedAsOpenerAndCloserIsRejected() {
        assertThatThrownBy(() -> new BlockKind(Set.of(".X"), ".x", Set.of(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uses a word twice");
    }

    @Test
    void aWordUsedAsDividerAndCloserIsRejected() {
        assertThatThrownBy(() -> new BlockKind(Set.of(".X"), ".ENDX", Set.of(".ENDX"), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uses a word twice");
    }

    @Test
    void aWordUsedAsOpenerAndDividerIsRejected() {
        assertThatThrownBy(() -> new BlockKind(Set.of(".X"), ".ENDX", Set.of(".x"), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uses a word twice");
    }

    @Test
    void theWordsAreKeptUpperCasedAndComparedInAnyCase() {
        BlockKind kind = new BlockKind(Set.of(".ifx", ".IfNx"), ".endx", Set.of(".elsex"), false);

        assertThat(kind.openers()).containsExactlyInAnyOrder(".IFX", ".IFNX");
        assertThat(kind.closer()).isEqualTo(".ENDX");
        assertThat(kind.words()).containsExactlyInAnyOrder(".IFX", ".IFNX", ".ENDX", ".ELSEX");
        assertThat(kind.isOpener(".Ifx")).isTrue();
        assertThat(kind.isOpener(".ELSEX")).isFalse();
        assertThat(kind.isCloser(".EndX")).isTrue();
        assertThat(kind.isCloser(".IFX")).isFalse();
    }
}
