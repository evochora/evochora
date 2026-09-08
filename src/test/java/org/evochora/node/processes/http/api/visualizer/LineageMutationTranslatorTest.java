package org.evochora.node.processes.http.api.visualizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void translatesTheValueBeforeTheWriteLikeTheValueAfterIt() {
        // A substitution over a label reference: the value it replaced stood in the same namespace
        // as the one it wrote, so the visualizer shows both through the same masks.
        int replacedLabel = new Molecule(Config.TYPE_LABELREF, 0x0abc).toInt();
        StoredMutationEvents generation1 = StoredMutationEvents.newBuilder()
                .setDimensions(2)
                .addEvents(substitution(replacedLabel, RECORDED_LABEL, -5, 0))
                .addEvents(labelRewrite(MASK_GENERATION_1))
                .build();

        List<MutationEventView> events = LineageMutationTranslator.translate(
                List.of(
                    entry(2, 2, mask(MASK_GENERATION_2)),
                    entry(1, 1, generation1)),
                WORLD);

        MutationEventView substitution = events.get(0);
        assertThat(substitution.cells().get(0).before().moleculeType()).isEqualTo(Config.TYPE_LABELREF);
        assertThat(substitution.cells().get(0).before().moleculeValue())
                .isEqualTo(0x0abc ^ MASK_GENERATION_1 ^ MASK_GENERATION_2);
        assertThat(substitution.cells().get(0).after().moleculeValue())
                .isEqualTo(0x1234 ^ MASK_GENERATION_1 ^ MASK_GENERATION_2);
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
        // Every event names the genome it arose in and that genome's parent
        assertThat(events.get(0).originGenomeHash()).isEqualTo("10");
        assertThat(events.get(0).originParentGenomeHash()).isNull();
        assertThat(events.get(1).originGenomeHash()).isEqualTo("20");
        assertThat(events.get(1).originParentGenomeHash()).isEqualTo("10");
    }

    @Test
    void refusesAnOrganismWhoseOriginDoesNotFitTheWorld() {
        LineageMutations flat = new LineageMutations(1, 1, 10L, null, 1L, new int[]{2}, mask(MASK_GENERATION_1));

        assertThatThrownBy(() -> LineageMutationTranslator.translate(List.of(flat), WORLD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no initial position in 2 dimensions");
    }

    @Test
    void refusesAnEventWhoseCellsDoNotAddUp() {
        StoredMutationEvent twoValuesOneCoordinate = StoredMutationEvent.newBuilder()
                .setPluginClass("org.evochora.runtime.worldgen.GeneSubstitutionPlugin")
                .setKind("substitution")
                .addRelativeCoordinates(-5)
                .addOldValues(0).addOldValues(0)
                .addNewValues(RECORDED_LABEL).addNewValues(RECORDED_LABEL)
                .build();
        StoredMutationEvents events = StoredMutationEvents.newBuilder()
                .setDimensions(2).addEvents(twoValuesOneCoordinate).build();

        assertThatThrownBy(() -> LineageMutationTranslator.translate(List.of(entry(1, 1, events)), WORLD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("describes 2 cells, but carries 2 old values and 1 coordinate components");
    }

    @Test
    void refusesAMaskThatCarriesNoValue() {
        StoredMutationEvent maskWithoutValue = StoredMutationEvent.newBuilder()
                .setPluginClass(LabelRewritePlugin.class.getName())
                .setKind(LabelRewritePlugin.MUTATION_KIND)
                .build();
        StoredMutationEvents events = StoredMutationEvents.newBuilder()
                .setDimensions(2).addEvents(maskWithoutValue).build();

        assertThatThrownBy(() -> LineageMutationTranslator.translate(List.of(entry(1, 1, events)), WORLD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carries no mask");
    }

    /**
     * Builds one organism of a chain, all of them starting at the same position so that the
     * displayed body is the one at (2, 3). Its parent's genome is the genome of the organism one
     * generation older, which is how the chain the reader returns is built.
     */
    private static LineageMutations entry(int organismId, int generation, StoredMutationEvents events) {
        return new LineageMutations(organismId, generation, organismId * 10L,
                organismId > 1 ? (organismId - 1) * 10L : null, organismId,
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
        return substitution(0, newValue, offsetX, offsetY);
    }

    private static StoredMutationEvent substitution(int oldValue, int newValue, int offsetX, int offsetY) {
        return StoredMutationEvent.newBuilder()
                .setPluginClass("org.evochora.runtime.worldgen.GeneSubstitutionPlugin")
                .setKind("substitution")
                .addRelativeCoordinates(offsetX)
                .addRelativeCoordinates(offsetY)
                .addOldValues(oldValue)
                .addNewValues(newValue)
                .build();
    }
}
