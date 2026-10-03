package org.evochora.node.processes.http.api.visualizer.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * How the organisms of one tick descend from a root: part of the answer to a tick request that
 * names a root.
 * <p>
 * The root is one organism, or the virtual root {@code all} above the founders, which travels as
 * id {@code 0}. Every child of the root is a <em>line</em>; every organism of the tick is
 * attributed to the line it descends from. While the ancestry of the run is still being read, or
 * after reading it failed, {@code lines} and {@code lineOf} are empty.
 *
 * @param state    {@code "ready"}, {@code "loading"} or {@code "failed"}
 * @param progress How much of the run's ancestry has been read, between 0 and 1
 * @param organismsInRun Organisms created in this run: the larger of the organisms created up to
 *                 the run's newest tick, as the index last found it, and the highest id the index
 *                 has read, less the fork boundary, so that the ids a fork took over from its
 *                 parent run do not count; 0 while the boundary is not known. With
 *                 {@code root.descendants} it gives the share of the run's organisms that do not
 *                 descend from the root, {@code (organismsInRun - root.descendants) / organismsInRun},
 *                 which the client shows for the organisms outside every line and clamps to
 *                 [0, 1]: the founders of a fork sit at or below the boundary, yet count among
 *                 the descendants.
 * @param error    Why reading the ancestry failed; present only in the failed state
 * @param root     The root; {@code null} while a requested {@code auto} root cannot be resolved:
 *                 while the ancestry is not ready, and while a living organism of the tick has an
 *                 ancestry that is not known yet, since the common ancestor would be resolved
 *                 without it. {@code lines} and {@code lineOf} are empty then, and the client
 *                 asks for {@code auto} again.
 * @param lines    Every child of the root, largest line first
 * @param up       Where the root moves upward; {@code null} for the virtual root, and while the
 *                 root's parent is not known
 * @param lineOf   Organism id of every organism of the tick to its line: the line's id, {@code 0}
 *                 for an organism not descended from the root, {@code -1} for an organism whose
 *                 ancestry is not known; the root itself maps to its own id
 * @param unreadLiving Living organisms of the tick whose ancestry is not known yet: their rows
 *                 are missing from the index, and the index is asked to read them again. They
 *                 map to {@code -1} in {@code lineOf}, and an {@code auto} root is resolved
 *                 over the others. The answer is complete once this is 0; a client that wants the
 *                 complete answer asks again while it is not
 */
public record DescentDto(
    String state,
    double progress,
    int organismsInRun,
    @JsonInclude(JsonInclude.Include.NON_NULL) String error,
    Root root,
    List<Line> lines,
    Up up,
    Map<Integer, Integer> lineOf,
    int unreadLiving
) {

    /**
     * The root of the descent.
     * <p>
     * For the virtual root {@code all} only {@code id} is present, and it is 0.
     *
     * @param id          Organism id, 0 for {@code all}
     * @param birthTick   Tick the root was born at
     * @param deathTick   Tick the root died at, -1 if no recorded tick has reported its death
     * @param position    Where the root was born
     * @param descendants Organisms descended from the root over the whole run, as far as their
     *                    ancestry is known; present only when the ancestry is ready
     */
    public record Root(
        int id,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long birthTick,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long deathTick,
        @JsonInclude(JsonInclude.Include.NON_NULL) int[] position,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long descendants
    ) {}

    /**
     * One line: a child of the root with everything descended from it.
     *
     * @param id          The child's organism id
     * @param descendants Members of the line over the whole run, the child included
     * @param colour      Rank of the line among the eight largest, 0 for the largest; {@code null}
     *                    for a line outside them
     * @param living      Members of the line alive at the tick
     * @param landing     Where a step down into this line lands when the generations through
     *                    which its living members ran through a single child are skipped; absent
     *                    when no member is alive or nothing would be skipped
     */
    public record Line(
        int id,
        long descendants,
        Integer colour,
        int living,
        @JsonInclude(JsonInclude.Include.NON_NULL) Landing landing
    ) {}

    /**
     * The destination of a skipping step.
     *
     * @param root    The organism that becomes the root, 0 for {@code all}
     * @param skipped The generations the step passes over
     */
    public record Landing(int root, int skipped) {}

    /**
     * The steps upward from the root.
     *
     * @param oneStep The root's parent, 0 when the root is a founder
     * @param landing The next ancestor above the root at which the living split into more than
     *                one line, or {@code all}; {@code skipped} counts the generations passed over
     *                beyond the one step. Absent when an ancestor on the way up is not known yet.
     */
    public record Up(int oneStep, @JsonInclude(JsonInclude.Include.NON_NULL) Landing landing) {}
}
