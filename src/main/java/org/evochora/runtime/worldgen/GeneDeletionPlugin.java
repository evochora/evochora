package org.evochora.runtime.worldgen;

import java.util.Arrays;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.evochora.runtime.Config;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.spi.IBirthHandler;
import org.evochora.runtime.spi.IRandomProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

/**
 * Gene deletion birth handler that removes code blocks from newborn organisms.
 * <p>
 * Called once per newborn organism in the post-Execute phase of each tick. With configurable
 * probability, selects a LABEL molecule and deletes everything in the organism's DV direction
 * until hitting the next LABEL (gene boundary), STRUCTURE (shell boundary), or a foreign molecule.
 * <p>
 * Labels that appear multiple times (from gene duplication) are selected with higher probability,
 * controlled by a configurable exponent: weight = count^exponent. With exponent=2.0 (default),
 * this yields quadratic scaling — biologically motivated by tandem repeat instability where
 * the probability of deletion through misalignment grows as O(N²) with repeat count.
 * <p>
 * The thermodynamic cost system (value-dependent POKE costs) provides the counterweight to
 * genome bloat from duplication. This plugin provides variation: redundant genes (from duplication)
 * are preferentially deleted (neutral), while unique genes are rarely hit (lethal, filtered by selection).
 * <p>
 * <strong>Thread Safety:</strong> Not thread-safe. Runs in the sequential post-Execute phase of
 * {@code Simulation.tick()}.
 *
 * @see org.evochora.runtime.spi.IBirthHandler
 * @see GeneDuplicationPlugin
 */
public class GeneDeletionPlugin implements IBirthHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GeneDeletionPlugin.class);

    private final Random random;
    private final double deletionRate;
    private final double countExponent;

    // Reusable collections (cleared before each use)
    private final IntArrayList labelFlatIndices = new IntArrayList();
    private final IntArrayList labelHashes = new IntArrayList();
    private final Int2IntOpenHashMap hashCounts = new Int2IntOpenHashMap();

    /**
     * Creates a gene deletion plugin from configuration.
     *
     * @param randomProvider Source of randomness.
     * @param config Configuration containing deletionRate and countExponent.
     */
    public GeneDeletionPlugin(IRandomProvider randomProvider, com.typesafe.config.Config config) {
        this.random = randomProvider.asJavaRandom();
        this.deletionRate = config.getDouble("deletionRate");
        this.countExponent = config.getDouble("countExponent");
        if (deletionRate < 0.0 || deletionRate > 1.0) {
            throw new IllegalArgumentException("deletionRate must be in [0.0, 1.0], got: " + deletionRate);
        }
        if (countExponent < 0.0) {
            throw new IllegalArgumentException("countExponent must be non-negative, got: " + countExponent);
        }
    }

    /**
     * Convenience constructor for tests.
     *
     * @param randomProvider Source of randomness.
     * @param deletionRate Probability of deletion per newborn (0.0 to 1.0).
     * @param countExponent Exponent for duplicate label weighting.
     */
    GeneDeletionPlugin(IRandomProvider randomProvider, double deletionRate, double countExponent) {
        this.random = randomProvider.asJavaRandom();
        this.deletionRate = deletionRate;
        this.countExponent = countExponent;
    }

    /** {@inheritDoc} */
    @Override
    public void onBirth(Organism child, Environment environment) {
        if (random.nextDouble() >= deletionRate) {
            return;
        }
        delete(child, environment);
    }

    /**
     * Performs gene deletion for a single newborn organism.
     * <p>
     * Collects all LABEL molecules owned by the child, selects one using weighted reservoir
     * sampling (weight = hashCount^countExponent), then walks in DV direction deleting all
     * molecules until hitting the next LABEL, STRUCTURE, or a foreign molecule.
     *
     * @param child The newborn organism.
     * @param env The simulation environment.
     */
    void delete(Organism child, Environment env) {
        int childId = child.getId();
        IntOpenHashSet owned = env.getCellsOwnedBy(childId);
        if (owned == null || owned.isEmpty()) {
            LOG.debug("tick={} Organism {} gene deletion: no owned cells", child.getBirthTick(), childId);
            return;
        }

        // --- Phase 1: Collect all labels and count hash frequencies ---
        labelFlatIndices.clear();
        labelHashes.clear();
        hashCounts.clear();

        owned.forEach((int flatIndex) -> {
            int moleculeInt = env.getMoleculeInt(flatIndex);
            if ((moleculeInt & Config.TYPE_MASK) == Config.TYPE_LABEL) {
                int hash = moleculeInt & Config.VALUE_MASK;
                labelFlatIndices.add(flatIndex);
                labelHashes.add(hash);
                hashCounts.addTo(hash, 1);
            }
        });

        if (labelFlatIndices.isEmpty()) {
            LOG.debug("tick={} Organism {} selected for deletion but has no labels", child.getBirthTick(), childId);
            return;
        }

        // --- Phase 2: Weighted reservoir sampling ---
        double totalWeight = 0.0;
        int selectedIdx = 0;

        for (int i = 0; i < labelFlatIndices.size(); i++) {
            int hash = labelHashes.getInt(i);
            double weight = Math.pow(hashCounts.get(hash), countExponent);
            totalWeight += weight;
            if (random.nextDouble() * totalWeight < weight) {
                selectedIdx = i;
            }
        }

        // --- Phase 3: Walk & Delete ---
        int selectedFlatIndex = labelFlatIndices.getInt(selectedIdx);
        int[] pos = env.properties.flatIndexToCoordinates(selectedFlatIndex);
        int[] dv = child.getDv();

        // Find DV dimension for safety limit
        int dvDim = -1;
        for (int i = 0; i < dv.length; i++) {
            if (dv[i] != 0) {
                dvDim = i;
                break;
            }
        }
        if (dvDim == -1) {
            LOG.debug("tick={} Organism {} gene deletion: degenerate DV", child.getBirthTick(), childId);
            return;
        }

        int maxSteps = env.getShape()[dvDim];

        // Delete the label itself
        env.setMolecule(new Molecule(Config.TYPE_CODE, 0), 0, pos);
        int deletedCount = 1;

        // Walk in DV direction
        for (int step = 0; step < maxSteps; step++) {
            pos = env.properties.getNextPosition(pos, dv);

            int owner = env.getOwnerId(pos);
            if (owner != 0 && owner != childId) {
                break; // foreign molecule
            }

            Molecule mol = env.getMolecule(pos);
            int type = mol.type();

            if (type == Config.TYPE_LABEL) {
                break; // next gene boundary
            }
            if (type == Config.TYPE_STRUCTURE) {
                break; // shell boundary
            }

            if (!mol.isEmpty()) {
                env.setMolecule(new Molecule(Config.TYPE_CODE, 0), 0, pos);
                deletedCount++;
            }
        }

        if (LOG.isDebugEnabled()) {
            int[] labelPos = env.properties.flatIndexToCoordinates(selectedFlatIndex);
            LOG.debug("tick={} Organism {} gene deletion: removed {} molecules from label hash {} at {}",
                    child.getBirthTick(), childId, deletedCount, labelHashes.getInt(selectedIdx), Arrays.toString(labelPos));
        }
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
