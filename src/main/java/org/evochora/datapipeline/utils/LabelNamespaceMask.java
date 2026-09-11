package org.evochora.datapipeline.utils;

import org.evochora.datapipeline.api.contracts.StoredMutationEvent;
import org.evochora.datapipeline.api.contracts.StoredMutationEvents;
import org.evochora.runtime.Config;
import org.evochora.runtime.worldgen.LabelRewritePlugin;

/**
 * The label namespace a body's LABEL and LABELREF molecules stand in, read from the births of its
 * ancestry.
 * <p>
 * Every newborn's labels and label references are XOR-masked once at birth
 * ({@link LabelRewritePlugin}), and every descendant's again at its own birth. A label value in a
 * body therefore equals the value the compiler gave it only for a founding organism; in every
 * later body it carries the masks of all births in between. XOR composes those masks exactly:
 * they are their own inverse and they commute, so one composed mask turns a value of one namespace
 * into the other and back.
 * <p>
 * Two compositions are needed, because two questions are asked of the same births. A body's own
 * values stand in the namespace of every mask its ancestry ever applied, which
 * {@link #ofChain(Iterable)} composes. A value a plugin <em>recorded</em> at a birth stands in the
 * namespace of that moment, and only the masks applied after it still have to be added, which
 * {@link #recordedAfter(StoredMutationEvents, int)} composes: a mask with a smaller event index ran
 * before the recording plugin, so the recorded value already carries it.
 * <p>
 * Label rewriting is core behaviour that happens to be implemented through the birth handler
 * interface. That is why this one kind of event is interpreted here while every other kind is
 * carried through as the plugin reported it.
 * <p>
 * <strong>Thread Safety:</strong> Stateless; all methods are static and operate only on their
 * arguments.
 */
public final class LabelNamespaceMask {

    private LabelNamespaceMask() {
        // Utility class
    }

    /**
     * Composes the mask that turns a compiled label value into the value it has in the body of an
     * organism whose ancestry is given.
     *
     * @param chain The births of an ancestry, each with the events it recorded, in any order —
     *              XOR does not depend on it
     * @return The composed mask, zero for an ancestry that never rewrote a label
     * @throws IllegalStateException if a label mask event carries no mask
     */
    public static int ofChain(Iterable<StoredMutationEvents> chain) {
        int composed = 0;
        for (final StoredMutationEvents events : chain) {
            composed ^= ofBirth(events);
        }
        return composed;
    }

    /**
     * Composes every label mask of one birth.
     *
     * @param events The events of that birth
     * @return The composed mask, zero when the birth rewrote no label
     * @throws IllegalStateException if a label mask event carries no mask
     */
    public static int ofBirth(StoredMutationEvents events) {
        int composed = 0;
        for (final StoredMutationEvent event : events.getEventsList()) {
            composed ^= of(event);
        }
        return composed;
    }

    /**
     * Composes the label masks of one birth that were applied after a given event of that birth.
     *
     * @param events The events of that birth
     * @param index Ordinal of the event the masks are composed for
     * @return The composed mask, zero when no later event of that birth is a label mask
     * @throws IllegalStateException if a label mask event carries no mask
     */
    public static int recordedAfter(StoredMutationEvents events, int index) {
        int composed = 0;
        for (int i = index + 1; i < events.getEventsCount(); i++) {
            composed ^= of(events.getEvents(i));
        }
        return composed;
    }

    /**
     * Reads the mask one event carries.
     *
     * @param event The event to read
     * @return The mask, narrowed to the bits a label value may use, or zero if the event is not a
     *         label mask
     * @throws IllegalStateException if a label mask event carries no mask
     */
    private static int of(StoredMutationEvent event) {
        if (!LabelRewritePlugin.MUTATION_KIND.equals(event.getKind())) {
            return 0;
        }
        if (event.getParamsCount() == 0) {
            throw new IllegalStateException("An event of kind '" + LabelRewritePlugin.MUTATION_KIND
                + "' reported by " + event.getPluginClass() + " carries no mask, so no label value"
                + " of its lineage can be placed in a later namespace");
        }
        return (int) (event.getParams(0) & Config.LABEL_VALUE_MASK);
    }
}
