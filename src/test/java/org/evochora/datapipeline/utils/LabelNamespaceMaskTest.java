package org.evochora.datapipeline.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.evochora.datapipeline.api.contracts.StoredMutationEvent;
import org.evochora.datapipeline.api.contracts.StoredMutationEvents;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.runtime.Config;
import org.evochora.runtime.worldgen.LabelRewritePlugin;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link LabelNamespaceMask}.
 * <p>
 * The subject is which births a mask is composed of. A body carries the masks of every birth of its
 * ancestry, while a value recorded during a birth carries only those of that birth that ran after
 * the recording, so the two compositions are checked against the same events.
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class LabelNamespaceMaskTest {

    private static StoredMutationEvent labelMask(long mask) {
        return StoredMutationEvent.newBuilder()
                .setPluginClass(LabelRewritePlugin.class.getName())
                .setKind(LabelRewritePlugin.MUTATION_KIND)
                .addParams(mask)
                .build();
    }

    private static StoredMutationEvent otherKind() {
        return StoredMutationEvent.newBuilder()
                .setPluginClass("org.evochora.runtime.worldgen.GeneSubstitutionPlugin")
                .setKind("substitution")
                .addParams(0x7FFFFL)
                .build();
    }

    private static StoredMutationEvents birth(StoredMutationEvent... events) {
        return StoredMutationEvents.newBuilder().addAllEvents(List.of(events)).build();
    }

    @Test
    void composesTheMasksOfEveryBirthOfAnAncestry() {
        final int composed = LabelNamespaceMask.ofChain(List.of(
                birth(labelMask(0x0000F)),
                birth(labelMask(0x000F0)),
                birth(labelMask(0x00F00))));

        assertThat(composed).isEqualTo(0x00FFF);
    }

    @Test
    void reportsNoMaskForAnAncestryThatRewroteNoLabel() {
        assertThat(LabelNamespaceMask.ofChain(List.of(birth(otherKind()), birth()))).isZero();
    }

    @Test
    void cancelsAMaskThatAnAncestryAppliedTwice() {
        // XOR is its own inverse, which is what lets one composed mask carry a value both ways
        final int composed = LabelNamespaceMask.ofChain(List.of(
                birth(labelMask(0x2AAAA)),
                birth(labelMask(0x2AAAA))));

        assertThat(composed).isZero();
    }

    @Test
    void composesEveryMaskOfOneBirthAndIgnoresOtherKinds() {
        assertThat(LabelNamespaceMask.ofBirth(birth(labelMask(0x000F0), otherKind(), labelMask(0x0000F))))
                .isEqualTo(0x000FF);
    }

    @Test
    void narrowsAMaskToTheBitsALabelValueUses() {
        // The plugin reports a full 64-bit parameter; a label value holds fewer bits than that
        assertThat(LabelNamespaceMask.ofBirth(birth(labelMask(-1L))))
                .isEqualTo(Config.LABEL_VALUE_MASK);
    }

    @Test
    void composesOnlyTheMasksOfABirthThatRanAfterTheRecordedEvent() {
        // The event the value was recorded at is a mask itself, so leaving it in would show
        final StoredMutationEvents events =
                birth(labelMask(0x0000F), labelMask(0x000F0), otherKind(), labelMask(0x00F00));

        assertThat(LabelNamespaceMask.recordedAfter(events, 1)).isEqualTo(0x00F00);
    }

    @Test
    void reportsNoMaskForTheLastEventOfABirth() {
        final StoredMutationEvents events = birth(otherKind(), labelMask(0x0000F));

        assertThat(LabelNamespaceMask.recordedAfter(events, events.getEventsCount() - 1)).isZero();
    }

    @Test
    void refusesALabelMaskEventThatCarriesNoMask() {
        final StoredMutationEvents events = birth(StoredMutationEvent.newBuilder()
                .setPluginClass(LabelRewritePlugin.class.getName())
                .setKind(LabelRewritePlugin.MUTATION_KIND)
                .build());

        assertThatThrownBy(() -> LabelNamespaceMask.ofBirth(events))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(LabelRewritePlugin.MUTATION_KIND)
                .hasMessageContaining(LabelRewritePlugin.class.getName());
    }
}
