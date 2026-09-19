package org.evochora.runtime.label;

import org.evochora.runtime.Config;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.MutationRecord;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.spi.ILabelMatchingStrategy;
import org.evochora.runtime.spi.IRandomProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Moves a newborn into the label namespace its label matching strategy chooses.
 * <p>
 * The strategy decides the mask ({@link ILabelMatchingStrategy#birthMask}); this class applies it:
 * every molecule of the newborn that carries a label address ({@link LabelAddress}) is XORed with
 * it. Because labels and label references move by the same mask, every Hamming distance inside the
 * organism stays as it was — {@code d(L^m, R^m) = d(L, R)} — and only the relation of the
 * newborn's labels to everybody else's changes.
 * <p>
 * The simulation runs this after the birth handlers of a newborn, so that labels and references
 * the mutation operators wrote are moved along with the inherited ones, and so that the order is
 * the same in every run: a consumer that compares a label value recorded by a mutation operator
 * with a later body relies on the rewrite of that birth coming after the record.
 * <p>
 * <strong>What it records:</strong> a rewrite that changed at least one molecule reports itself on
 * the newborn as a {@link MutationRecord} of kind {@link #MUTATION_KIND} with no cells and the mask
 * as its one parameter, naming the strategy's class as its source. It is not a mutation — the
 * genome hash normalizes a uniform mask away — but without the mask a consumer cannot compare a
 * label value across generations. A consumer that wants a recorded value as it stands in a given
 * body XORs the masks of every birth from the recording organism down to that body, and within the
 * recording birth only those masks that were recorded after the mutation. A mask of 0 and a
 * newborn without label addresses record nothing.
 * <p>
 * <strong>Thread Safety:</strong> Not thread-safe. Runs in the sequential post-execute phase of
 * {@code Simulation.tick()}.
 */
public final class LabelRewrite {

    private static final Logger LOG = LoggerFactory.getLogger(LabelRewrite.class);

    /**
     * The kind a rewrite is recorded under.
     * <p>
     * Public because a consumer that compares a recorded label value with a body has to recognise
     * these events among the mutations of a lineage and compose their masks.
     */
    public static final String MUTATION_KIND = "label-rewrite";

    /** Collects the record of a rewrite; reused so that a birth allocates only the record itself. */
    private final MutationRecord.Builder recordBuilder = new MutationRecord.Builder();

    /**
     * Asks the strategy for the newborn's mask and applies it to every label address the newborn
     * owns. A newborn without cells is left alone and the strategy is not asked, so that it draws
     * no random number for it.
     *
     * @param child The newly born organism
     * @param environment The simulation environment, whose label index holds the strategy
     * @param randomProvider The root random provider the strategy may draw the mask from
     * @throws IllegalStateException if the strategy returns a mask outside the value field
     */
    public void apply(Organism child, Environment environment, IRandomProvider randomProvider) {
        if (environment.countCellsOwnedBy(child.getId()) == 0) {
            return;
        }
        ILabelMatchingStrategy strategy = environment.getLabelIndex().getStrategy();
        int mask = strategy.birthMask(randomProvider);
        if (mask == 0) {
            return;
        }
        if ((mask & ~Config.VALUE_MASK) != 0) {
            throw new IllegalStateException("Label matching strategy " + strategy.getClass().getName()
                    + " returned the birth mask 0x" + Integer.toHexString(mask)
                    + ", which leaves the value field");
        }

        final int[] rewriteCount = {0};
        environment.visitCellsOwnedBy(child.getId(), cell -> {
            int moleculeInt = cell.moleculeInt();
            if (LabelAddress.isCarriedBy(moleculeInt)) {
                cell.setMolecule(Molecule.fromInt(moleculeInt ^ mask));
                rewriteCount[0]++;
            }
        });

        // Only a mask that moved a molecule is worth reporting; a genome without label addresses is
        // unchanged, and an event that says nothing happened would have to be filtered out again
        if (rewriteCount[0] > 0) {
            child.recordBirthMutation(recordBuilder
                    .start(strategy.getClass().getName(), MUTATION_KIND, child.getDv())
                    .param(mask)
                    .build());
        }

        LOG.debug("tick={} Organism {} label rewrite: rewrote {} molecules with mask={}",
                child.getBirthTick(), child.getId(), rewriteCount[0], Integer.toHexString(mask));
    }
}
