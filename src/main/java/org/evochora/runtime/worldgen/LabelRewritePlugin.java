package org.evochora.runtime.worldgen;

import java.util.Random;

import org.evochora.runtime.Config;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.spi.IBirthHandler;
import org.evochora.runtime.spi.IRandomProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Birth handler that gives each newborn organism a unique label namespace by XOR-rewriting
 * all LABEL and LABELREF molecules with a random 19-bit mask.
 * <p>
 * Without this plugin, children inherit the exact label hash values of their parents, causing
 * most organisms in the population to share identical labels. While the fuzzy jump system's
 * {@code foreignPenalty} favours own labels, physically closer foreign labels with identical
 * hashes can still win the scoring. This leads to unintended cross-organism jumps.
 * <p>
 * The XOR rewrite solves this by mapping each newborn's labels into a private namespace.
 * Because the same mask is applied to every LABEL and LABELREF molecule, all Hamming distances
 * between label/labelref pairs are exactly preserved: {@code d(L^m, R^m) = d(L, R)}. This
 * means the organism's internal fuzzy jump behaviour is unchanged — only cross-organism
 * label collisions are eliminated.
 * <p>
 * Parasitism remains possible: a parasite can evolve labelrefs that match a host's rewritten
 * labels through mutation. The difference is that this now requires genuine evolutionary
 * adaptation rather than happening by accident through shared label values.
 * <p>
 * <strong>Performance:</strong> Visits only the newborn's owned cells through
 * {@link Environment#visitCellsOwnedBy}, reading the packed molecule int from each cell view
 * and writing the rewritten molecule back through the same view. Per birth this allocates the
 * visitor lambda, its rewrite counter and one {@link Molecule} record for each cell that is
 * actually a LABEL or LABELREF (typically 5–20 per organism); the GC pressure is negligible.
 * <p>
 * <strong>Thread Safety:</strong> Not thread-safe. Runs in the sequential post-Execute phase
 * of {@code Simulation.tick()}.
 *
 * @see org.evochora.runtime.spi.IBirthHandler
 * @see org.evochora.runtime.label.PreExpandedHammingStrategy
 */
public class LabelRewritePlugin implements IBirthHandler {

    private static final Logger LOG = LoggerFactory.getLogger(LabelRewritePlugin.class);


    private final Random random;

    /**
     * Creates a label rewrite plugin.
     *
     * @param randomProvider Source of randomness for generating XOR masks.
     * @param config Plugin configuration (currently unused; reserved for future options).
     */
    public LabelRewritePlugin(IRandomProvider randomProvider, com.typesafe.config.Config config) {
        this.random = randomProvider.asJavaRandom();
    }

    /**
     * Package-private convenience constructor for tests.
     *
     * @param randomProvider Source of randomness for generating XOR masks.
     */
    LabelRewritePlugin(IRandomProvider randomProvider) {
        this.random = randomProvider.asJavaRandom();
    }

    /**
     * Rewrites all LABEL and LABELREF molecules owned by the newborn with a random XOR mask.
     * <p>
     * The mask is a non-zero 19-bit value. Applying the same mask to both labels and labelrefs
     * preserves all Hamming distances, so the organism's internal fuzzy jump behaviour is unchanged.
     *
     * @param child The newly born organism.
     * @param environment The simulation environment.
     */
    @Override
    public void onBirth(Organism child, Environment environment) {
        if (environment.countCellsOwnedBy(child.getId()) == 0) {
            LOG.debug("tick={} Organism {} label rewrite: no owned cells", child.getBirthTick(), child.getId());
            return;
        }

        int mask = random.nextInt(Config.LABEL_VALUE_MASK) + 1; // [1, LABEL_VALUE_MASK], never zero
        final int[] rewriteCount = {0};

        environment.visitCellsOwnedBy(child.getId(), cell -> {
            int moleculeInt = cell.moleculeInt();
            int type = moleculeInt & Config.TYPE_MASK;

            if (type == Config.TYPE_LABEL || type == Config.TYPE_LABELREF) {
                int oldValue = moleculeInt & Config.VALUE_MASK;
                int newValue = oldValue ^ mask;
                int marker = (moleculeInt & Config.MARKER_MASK) >>> Config.MARKER_SHIFT;
                cell.setMolecule(new Molecule(type, newValue, marker));
                rewriteCount[0]++;
            }
        });

        LOG.debug("tick={} Organism {} label rewrite: rewrote {} molecules with mask={}",
                child.getBirthTick(), child.getId(), rewriteCount[0], Integer.toHexString(mask));
    }

    /** {@inheritDoc} */
    @Override
    public byte[] saveState() {
        return new byte[0];
    }

    /** {@inheritDoc} */
    @Override
    public void loadState(byte[] state) {
        // Stateless plugin - nothing to restore
    }
}
