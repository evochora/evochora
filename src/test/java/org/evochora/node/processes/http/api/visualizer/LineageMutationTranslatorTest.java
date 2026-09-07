package org.evochora.node.processes.http.api.visualizer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.evochora.datapipeline.api.contracts.StoredMutationEvent;
import org.evochora.datapipeline.api.contracts.StoredMutationEvents;
import org.evochora.datapipeline.api.resources.database.dto.LineageMutations;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.node.processes.http.api.visualizer.dto.OrganismMutationsResponseDto.MutationEventView;
import org.evochora.runtime.Config;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.worldgen.LabelRewritePlugin;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link LineageMutationTranslator}.
 * <p>
 * The subject is the label namespace: a recorded label value is masked once per birth between the
 * record and the body it is displayed on, so the tests compose masks across generations and check
 * that molecules of other types stay as they were recorded.
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class LineageMutationTranslatorTest {

    private static final EnvironmentProperties WORLD = new EnvironmentProperties(new int[]{100, 100}, true);

    private static final int RECORDED_LABEL = new Molecule(Config.TYPE_LABELREF, 0x1234).toInt();
    private static final int MASK_GENERATION_1 = 0x1;
    private static final int MASK_GENERATION_2 = 0x2;
    private static final int MASK_GENERATION_3 = 0x4;

    @Test
    void composesTheMasksOfEveryBirthBetweenTheRecordAndTheDisplayedBody() {
        StoredMutationEvents generation1 = StoredMutationEvents.newBuilder()
                .setDimensions(2)
                .addEvents(substitution(RECORDED_LABEL, -5, 0))
                .addEvents(labelRewrite(MASK_GENERATION_1))
                .build();

        List<MutationEventView> events = LineageMutationTranslator.translate(
                List.of(
                    entry(3, 3, mask(MASK_GENERATION_3)),
                    entry(2, 2, mask(MASK_GENERATION_2)),
                    entry(1, 1, generation1)),
                WORLD);

        // Three masks and one substitution, oldest generation first
        assertThat(events).extracting(MutationEventView::originOrganismId)
                .containsExactly(1, 1, 2, 3);
        assertThat(events).extracting(MutationEventView::eventIndex)
                .containsExactly(0, 1, 0, 0);

        MutationEventView substitution = events.get(0);
        assertThat(substitution.kind()).isEqualTo("substitution");
        assertThat(substitution.cells()).hasSize(1);
        assertThat(substitution.cells().get(0).after().moleculeValue())
                .isEqualTo(0x1234 ^ MASK_GENERATION_1 ^ MASK_GENERATION_2 ^ MASK_GENERATION_3);
        assertThat(substitution.cells().get(0).after().moleculeType()).isEqualTo(Config.TYPE_LABELREF);
    }

    @Test
    void leavesAnEventThatWasRecordedAfterTheMaskOfItsOwnBirthAlone() {
        StoredMutationEvents ownBirth = StoredMutationEvents.newBuilder()
                .setDimensions(2)
                .addEvents(labelRewrite(MASK_GENERATION_1))
                .addEvents(substitution(RECORDED_LABEL, 0, 0))
                .build();

        List<MutationEventView> events = LineageMutationTranslator.translate(
                List.of(entry(1, 1, ownBirth)), WORLD);

        MutationEventView substitution = events.get(1);
        assertThat(substitution.kind()).isEqualTo("substitution");
        assertThat(substitution.cells().get(0).after().moleculeValue()).isEqualTo(0x1234);
    }

    @Test
    void leavesAMoleculeThatIsNeitherLabelNorLabelReferenceAsItWasRecorded() {
        int recordedCode = new Molecule(Config.TYPE_CODE, 0x1234).toInt();
        StoredMutationEvents generation1 = StoredMutationEvents.newBuilder()
                .setDimensions(2)
                .addEvents(substitution(recordedCode, 0, 0))
                .addEvents(labelRewrite(MASK_GENERATION_1))
                .build();

        List<MutationEventView> events = LineageMutationTranslator.translate(
                List.of(
                    entry(2, 2, mask(MASK_GENERATION_2)),
                    entry(1, 1, generation1)),
                WORLD);

        assertThat(events.get(0).cells().get(0).after().moleculeValue()).isEqualTo(0x1234);
        assertThat(events.get(0).cells().get(0).after().moleculeType()).isEqualTo(Config.TYPE_CODE);
    }

    @Test
    void placesACellOnTheDisplayedBodyAndReportsAMaskWithoutCells() {
        List<MutationEventView> events = LineageMutationTranslator.translate(
                List.of(
                    entry(2, 2, mask(MASK_GENERATION_2)),
                    entry(1, 1, StoredMutationEvents.newBuilder()
                        .setDimensions(2)
                        .addEvents(substitution(RECORDED_LABEL, -5, 0))
                        .build())),
                WORLD);

        // The displayed organism starts at (2, 3), so an offset of -5 wraps around the world
        assertThat(events.get(0).cells().get(0).coordinates()).containsExactly(97, 3);
        assertThat(events.get(1).kind()).isEqualTo(LabelRewritePlugin.MUTATION_KIND);
        assertThat(events.get(1).cells()).isEmpty();
        assertThat(events.get(1).params()).containsExactly(MASK_GENERATION_2);
    }

    /**
     * Builds one organism of a chain, all of them starting at the same position so that the
     * displayed body is the one at (2, 3).
     */
    private static LineageMutations entry(int organismId, int generation, StoredMutationEvents events) {
        return new LineageMutations(organismId, generation, organismId * 10L, organismId,
                new int[]{2, 3}, events);
    }

    private static StoredMutationEvents mask(int value) {
        return StoredMutationEvents.newBuilder().setDimensions(2).addEvents(labelRewrite(value)).build();
    }

    private static StoredMutationEvent labelRewrite(int value) {
        return StoredMutationEvent.newBuilder()
                .setPluginClass(LabelRewritePlugin.class.getName())
                .setKind(LabelRewritePlugin.MUTATION_KIND)
                .addParams(value)
                .build();
    }

    private static StoredMutationEvent substitution(int newValue, int offsetX, int offsetY) {
        return StoredMutationEvent.newBuilder()
                .setPluginClass("org.evochora.runtime.worldgen.GeneSubstitutionPlugin")
                .setKind("substitution")
                .addRelativeCoordinates(offsetX)
                .addRelativeCoordinates(offsetY)
                .addOldValues(0)
                .addNewValues(newValue)
                .build();
    }
}
