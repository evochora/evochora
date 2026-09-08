package org.evochora.datapipeline.services.analytics.plugins;

import java.util.List;

import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.runtime.model.EnvironmentProperties;

/**
 * Turns the absolute flat index of a cell into an offset from an organism's own origin.
 * <p>
 * A mutation an ancestor received sits at the same offset from every descendant's origin, so an
 * offset is what makes the events of a lineage comparable at all. The offset is taken along the
 * shortest way around the world, by the rule the genome hash is built with, so that the positions
 * of a row and of the hash it carries agree.
 * <p>
 * <strong>The text form.</strong> A position, a direction vector and a plugin's parameters are all
 * lists of numbers in a single column, written as a JSON list such as {@code [13,4]}. DuckDB reads
 * such a column as a typed list with a cast, {@code position::INTEGER[]} or
 * {@code params::BIGINT[]}, so every list column of the mutation tables is read the same way.
 * <p>
 * <strong>Thread safety:</strong> not thread-safe. An instance carries a scratch buffer and belongs
 * to the plugin that created it, which the indexer drives from one thread.
 */
final class RelativeCellPositions {

    /** Names the metric in the messages, so a broken recording says which table it broke. */
    private final String metricId;

    /** The world the run took place in, needed to turn a flat index into a coordinate. */
    private final EnvironmentProperties world;

    /** Reused across cells so that turning a flat index into a coordinate allocates nothing. */
    private final int[] coordinate;

    /** Reused by {@link #jsonOffsetOf(int, OrganismState)}, which needs no buffer of its own. */
    private final int[] offsets;

    /**
     * @param metricId the metric these positions are written for, for the messages
     * @param world the world the run took place in
     */
    RelativeCellPositions(String metricId, EnvironmentProperties world) {
        this.metricId = metricId;
        this.world = world;
        this.coordinate = new int[world.getDimensions()];
        this.offsets = new int[world.getDimensions()];
    }

    /**
     * @return the number of components a position in this world has
     */
    int dimensions() {
        return coordinate.length;
    }

    /**
     * Writes a cell's position relative to the organism's own origin into the given array.
     *
     * @param flatIndex the cell's absolute flat index, as the reporting plugin held it
     * @param org the organism the event belongs to, for its initial position
     * @param target receives one offset per dimension; must have {@link #dimensions()} entries
     * @throws IllegalStateException if the organism states an origin that does not fit this world
     */
    void offsetsOf(int flatIndex, OrganismState org, int[] target) {
        if (org.getInitialPosition().getComponentsCount() != coordinate.length) {
            throw new IllegalStateException("Metric '" + metricId + "': organism "
                + org.getOrganismId() + " states an initial position with "
                + org.getInitialPosition().getComponentsCount() + " components in a "
                + coordinate.length + "-dimensional world, so no offset can be computed for it");
        }
        world.flatIndexToCoordinates(flatIndex, coordinate);
        for (int d = 0; d < coordinate.length; d++) {
            target[d] = EnvironmentProperties.relativeOffset(
                coordinate[d],
                org.getInitialPosition().getComponents(d),
                world.getDimensionSize(d),
                world.isToroidal());
        }
    }

    /**
     * Renders a cell's position relative to the organism's own origin as a JSON list.
     *
     * @param flatIndex the cell's absolute flat index, as the reporting plugin held it
     * @param org the organism the event belongs to, for its initial position
     * @return the offset per dimension, for example {@code [13,4]}
     * @throws IllegalStateException if the organism states an origin that does not fit this world
     */
    String jsonOffsetOf(int flatIndex, OrganismState org) {
        offsetsOf(flatIndex, org, offsets);
        return asJsonList(offsets);
    }

    /**
     * Renders numbers as a JSON list.
     *
     * @param values the numbers, in the order they belong in
     * @return the list, for example {@code [13,4]}, or {@code []} for no values
     */
    static String asJsonList(int[] values) {
        StringBuilder text = new StringBuilder(2 + 4 * values.length);
        text.append('[');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(values[i]);
        }
        return text.append(']').toString();
    }

    /**
     * Renders numbers as a JSON list.
     *
     * @param values the numbers, in the order they belong in
     * @return the list, for example {@code [123]}, or {@code []} for no values
     */
    static String asJsonList(List<? extends Number> values) {
        StringBuilder text = new StringBuilder(2 + 4 * values.size());
        text.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(values.get(i));
        }
        return text.append(']').toString();
    }
}
