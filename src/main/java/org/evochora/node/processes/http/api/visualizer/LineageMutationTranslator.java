package org.evochora.node.processes.http.api.visualizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.evochora.datapipeline.api.contracts.StoredMutationEvent;
import org.evochora.datapipeline.api.contracts.StoredMutationEvents;
import org.evochora.datapipeline.api.resources.database.dto.LineageMutations;
import org.evochora.datapipeline.utils.LabelNamespaceMask;
import org.evochora.node.processes.http.api.visualizer.dto.OrganismMutationsResponseDto.MoleculeView;
import org.evochora.node.processes.http.api.visualizer.dto.OrganismMutationsResponseDto.MutationCellView;
import org.evochora.node.processes.http.api.visualizer.dto.OrganismMutationsResponseDto.MutationEventView;
import org.evochora.runtime.Config;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Molecule;

/**
 * Turns the stored mutations of an ancestry chain into the events of one displayed body.
 * <p>
 * Two things separate a stored event from what a display of the selected organism needs. The
 * first is position: an event carries its cells as offsets from the origin of the organism that
 * received it, so the displayed organism's own initial position turns them into world
 * coordinates. The second is the label namespace.
 * <p>
 * <strong>Why label values are translated.</strong> The value a mutation plugin recorded for a
 * LABEL or LABELREF cell equals the value in no later body: between the record and the body stand
 * the label masks of one birth after another. {@link LabelNamespaceMask} composes them, and
 * {@link LabelNamespaceMask#recordedAfter} is the composition a record needs — the masks of the
 * recording birth that were applied after the plugin wrote.
 * <p>
 * A mask touches only the value bits of a molecule — it is drawn from
 * {@link Config#LABEL_VALUE_MASK}, which is narrower than the value field — so it is applied to
 * the packed molecule directly. Molecules of any other type pass through untouched: they were
 * never masked.
 * <p>
 * <strong>Thread Safety:</strong> Stateless; all methods are static and operate only on their
 * arguments.
 */
final class LineageMutationTranslator {

    private LineageMutationTranslator() {
        // Utility class
    }

    /**
     * Places and translates every event of an ancestry chain onto the body of its first entry.
     *
     * @param chain The organisms of the chain with their birth mutations, the displayed organism
     *              first and the oldest ancestor last, as the reader returns them
     * @param world The world the run took place in, which the offsets are resolved against
     * @return The events of the whole chain, ordered by the generation they arose in and, within
     *         one birth, by the order the plugins reported them
     * @throws IllegalStateException if a stored event does not describe the world of the run or
     *                               does not carry what its kind requires
     */
    static List<MutationEventView> translate(final List<LineageMutations> chain,
                                             final EnvironmentProperties world) {
        final int[] anchor = chain.get(0).initialPosition();
        if (anchor == null || anchor.length != world.getDimensions()) {
            throw new IllegalStateException("Organism " + chain.get(0).organismId()
                + " has no initial position in " + world.getDimensions() + " dimensions, which the"
                + " coordinates of its lineage's mutations would be measured from");
        }

        final List<MutationEventView> views = new ArrayList<>();
        // Masks of the births between the displayed organism and the one an event is read from:
        // walking towards the ancestors, every organism passed adds its own mask to the composition
        // that later births applied to what an older one recorded.
        int descendantMasks = 0;
        for (final LineageMutations entry : chain) {
            final StoredMutationEvents events = entry.events();
            for (int index = 0; index < events.getEventsCount(); index++) {
                views.add(view(entry, index,
                    descendantMasks ^ LabelNamespaceMask.recordedAfter(events, index), anchor, world));
            }
            descendantMasks ^= LabelNamespaceMask.ofBirth(events);
        }

        views.sort(Comparator.comparingInt(MutationEventView::originGeneration)
            .thenComparingInt(MutationEventView::eventIndex));
        return views;
    }

    /**
     * Builds the view of one event of one birth.
     *
     * @param entry The organism the event was recorded at
     * @param index Ordinal of the event within that birth
     * @param mask The label masks that stand between this event and the displayed body
     * @param anchor Initial position of the displayed organism
     * @param world The world the run took place in
     * @return The event as the response carries it
     */
    private static MutationEventView view(final LineageMutations entry,
                                          final int index,
                                          final int mask,
                                          final int[] anchor,
                                          final EnvironmentProperties world) {
        final StoredMutationEvent event = entry.events().getEvents(index);
        final int dimensions = world.getDimensions();
        final int cellCount = event.getNewValuesCount();
        if (event.getOldValuesCount() != cellCount
                || event.getRelativeCoordinatesCount() != cellCount * dimensions) {
            throw new IllegalStateException("Mutation event " + index + " of organism "
                + entry.organismId() + " describes " + cellCount + " cells, but carries "
                + event.getOldValuesCount() + " old values and "
                + event.getRelativeCoordinatesCount() + " coordinate components for a "
                + dimensions + "-dimensional world");
        }

        final List<MutationCellView> cells = new ArrayList<>(cellCount);
        final int[] relative = new int[dimensions];
        for (int cell = 0; cell < cellCount; cell++) {
            for (int d = 0; d < dimensions; d++) {
                relative[d] = event.getRelativeCoordinates(cell * dimensions + d);
            }
            cells.add(new MutationCellView(
                world.getTargetCoordinate(anchor, relative),
                molecule(translate(event.getOldValues(cell), mask)),
                molecule(translate(event.getNewValues(cell), mask))));
        }

        final int[] dv = new int[event.getDvCount()];
        for (int d = 0; d < dv.length; d++) {
            dv[d] = event.getDv(d);
        }
        final long[] params = new long[event.getParamsCount()];
        for (int p = 0; p < params.length; p++) {
            params[p] = event.getParams(p);
        }

        return new MutationEventView(
            entry.organismId(),
            entry.generation(),
            String.valueOf(entry.genomeHash()),
            entry.parentGenomeHash() != null ? String.valueOf(entry.parentGenomeHash()) : null,
            entry.birthTick(),
            index,
            event.getPluginClass(),
            event.getKind(),
            dv,
            params,
            cells);
    }

    /**
     * Moves a recorded molecule into the label namespace of the displayed body.
     *
     * @param moleculeInt The packed molecule as it was recorded
     * @param mask The composed mask of the births between the record and that body
     * @return The packed molecule as it stands in that body, unchanged for a molecule that is
     *         neither a label nor a label reference
     */
    private static int translate(final int moleculeInt, final int mask) {
        final int type = moleculeInt & Config.TYPE_MASK;
        if (type != Config.TYPE_LABEL && type != Config.TYPE_LABELREF) {
            return moleculeInt;
        }
        return moleculeInt ^ mask;
    }

    /**
     * Splits a packed molecule the way the environment endpoint splits a cell.
     *
     * @param moleculeInt The packed molecule
     * @return Its type and its signed value
     */
    private static MoleculeView molecule(final int moleculeInt) {
        return new MoleculeView(moleculeInt & Config.TYPE_MASK, Molecule.extractSignedValue(moleculeInt));
    }
}
