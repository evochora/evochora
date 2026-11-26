package org.evochora.datapipeline.services.analytics.plugins;

import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.contracts.MutationEvent;
import org.evochora.datapipeline.api.contracts.OrganismState;

/**
 * Sorts a birth by what made the newborn's genome what it is.
 * <p>
 * <strong>The classes:</strong>
 * <ul>
 *   <li>{@code unchanged} - genome equal to the parent's, copied without a difference</li>
 *   <li>{@code bodiless} - newborn without a genome at all</li>
 *   <li>{@code no_event} - genome differs and no plugin claims it: a defective copy, or cells
 *       another organism overwrote</li>
 *   <li>{@code duplication}, {@code deletion}, {@code instruction_insertion},
 *       {@code label_insertion}, {@code substitution} - the birth carries events of exactly this
 *       one kind</li>
 *   <li>{@code multiple} - the birth carries events of two or more kinds, so two or more plugins
 *       changed this genome</li>
 *   <li>{@code other} - the birth carries events of exactly one kind, and that kind is none of the
 *       five above: a mutation plugin from outside this project</li>
 * </ul>
 * <p>
 * <strong>How a birth is sorted.</strong> Every birth falls into exactly one class. The genome
 * decides before the events do: a newborn without a genome has nothing that could have varied,
 * whatever its parent carried, and one whose genome is the parent's received no variation even
 * where a plugin wrote outside the body. Only then do the events of the birth that wrote cells
 * decide, by their kinds. An event that wrote no cell - the label mask, which changes every label
 * by the same amount and no molecule of its own - is not a variation and is left out, so a birth
 * that carries only such an event reads as whatever its genome says.
 * <p>
 * This is the one place a birth is classified: the metrics that count the classes per recording and
 * the ones that name the class of a single birth read the same order and the same rule, so a class
 * means the same thing wherever it is written.
 */
final class BirthVariation {

    /**
     * The classes, in the order they are numbered in: the two the genome alone decides, the
     * remainder no plugin explains, the kinds, and the two that catch what a single known kind does
     * not cover.
     */
    static final List<String> CLASSES = List.of(
        "unchanged",
        "bodiless",
        "no_event",
        "duplication",
        "deletion",
        "instruction_insertion",
        "label_insertion",
        "substitution",
        "multiple",
        "other");

    /**
     * What each class says about a birth, as a clause that completes "a birth where ...". The
     * tables that count the classes or name the class of a single birth describe themselves with
     * these, so that a class reads the same in every table.
     */
    private static final Map<String, String> MEANINGS = Map.of(
        "unchanged", "the genome equals the parent's",
        "bodiless", "the newborn has no genome",
        "no_event", "the genome differs from the parent's and no mutation plugin reported a change: "
            + "a defective copy, or cells another organism overwrote",
        "duplication", "the genome was changed by duplication only",
        "deletion", "the genome was changed by deletion only",
        "instruction_insertion", "the genome was changed by instruction insertion only",
        "label_insertion", "the genome was changed by label insertion only",
        "substitution", "the genome was changed by substitution only",
        "multiple", "the genome was changed by mutations of two or more kinds",
        "other", "the genome was changed by one kind of mutation that has no class of its own, "
            + "which a mutation plugin from outside Evochora reports");

    private static final int UNCHANGED = CLASSES.indexOf("unchanged");
    private static final int BODILESS = CLASSES.indexOf("bodiless");
    private static final int NO_EVENT = CLASSES.indexOf("no_event");
    private static final int MULTIPLE = CLASSES.indexOf("multiple");
    private static final int OTHER = CLASSES.indexOf("other");

    /**
     * The class of each mutation kind this project's plugins report. A kind outside this map has no
     * class of its own and falls under {@code other}, so a plugin brought from elsewhere shows up as
     * its own case instead of disappearing into one of these.
     */
    private static final Map<String, Integer> CLASS_OF_KIND = Map.of(
        "duplication", CLASSES.indexOf("duplication"),
        "deletion", CLASSES.indexOf("deletion"),
        "instruction-insertion", CLASSES.indexOf("instruction_insertion"),
        "label-insertion", CLASSES.indexOf("label_insertion"),
        "substitution", CLASSES.indexOf("substitution"));

    private BirthVariation() {
    }

    /**
     * Says what a class means.
     *
     * @param birthClass one of {@link #CLASSES}
     * @return a clause that completes "a birth where ..."
     * @throws IllegalArgumentException if the class is none of {@link #CLASSES}
     */
    static String meaningOf(String birthClass) {
        String meaning = MEANINGS.get(birthClass);
        if (meaning == null) {
            throw new IllegalArgumentException("No such class of birth: " + birthClass);
        }
        return meaning;
    }

    /**
     * Lists the classes with the number each is written as and with what it means.
     *
     * @return one sentence per class, such as "0: the genome equals the parent's."
     */
    static String numberedClasses() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < CLASSES.size(); i++) {
            if (i > 0) {
                text.append(' ');
            }
            text.append(i).append(" (").append(CLASSES.get(i)).append("): ")
                .append(meaningOf(CLASSES.get(i))).append('.');
        }
        return text.toString();
    }

    /**
     * Finds the class one birth belongs to.
     *
     * @param org the newborn, with its genome, its parent's and the events of its birth
     * @return the index of the class within {@link #CLASSES}
     */
    static int classify(OrganismState org) {
        if (org.getGenomeHash() == 0L) {
            return BODILESS;
        }
        if (org.hasParentGenomeHash() && org.getGenomeHash() == org.getParentGenomeHash()) {
            return UNCHANGED;
        }
        String kind = null;
        for (MutationEvent event : org.getBirthMutationsList()) {
            if (event.getCellsCount() == 0) {
                continue;
            }
            if (kind == null) {
                kind = event.getKind();
            } else if (!kind.equals(event.getKind())) {
                return MULTIPLE;
            }
        }
        if (kind == null) {
            return NO_EVENT;
        }
        return CLASS_OF_KIND.getOrDefault(kind, OTHER);
    }
}
