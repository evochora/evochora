package org.evochora.datapipeline.api.resources.database.dto;

import java.util.Objects;

/**
 * One page of the parent relation: organism ids in ascending order, each with the id of its parent.
 * <p>
 * The two arrays are parallel: {@code parents[i]} is the parent of {@code ids[i]}. An organism
 * without a parent carries {@code 0}, which is never an organism id, since ids start at 1.
 * <p>
 * The arrays are handed over, not copied, and must not be changed by anyone after construction.
 * A page is usually large, and copying it would double the transient heap of every read.
 *
 * @param ids     Organism ids, strictly ascending
 * @param parents Parent id per organism, {@code 0} for an organism without a parent
 */
public record ParentRows(int[] ids, int[] parents) {

    /**
     * Checks that the two arrays describe the same organisms.
     *
     * @throws NullPointerException     if either array is null
     * @throws IllegalArgumentException if the arrays differ in length
     */
    public ParentRows {
        Objects.requireNonNull(ids, "ids");
        Objects.requireNonNull(parents, "parents");
        if (ids.length != parents.length) {
            throw new IllegalArgumentException(
                "ids and parents differ in length: " + ids.length + " != " + parents.length);
        }
    }

    /**
     * The number of organisms on this page.
     *
     * @return The length of both arrays
     */
    public int size() {
        return ids.length;
    }
}
