package org.evochora.runtime.worldgen;

import java.util.Arrays;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.evochora.runtime.Config;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.MutationRecord;
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
 * until hitting the next LABEL (block boundary), STRUCTURE (shell boundary), or a foreign molecule.
 * <p>
 * <strong>Which labels are candidates:</strong> only those whose value the newborn's body holds at
 * least {@code minLabelCount} times among its LABEL cells. At the default of 2 the deletion removes
 * only a block the body carries twice, so what it takes away stands elsewhere and the deletion is
 * neutral by construction — the counterpart of duplication and of the label insertion's detour,
 * whose label copies the value of an existing one. A label occurring fewer times is not drawn from
 * at all; {@code minLabelCount = 1} makes every label a candidate again.
 * <p>
 * Among the candidates a label is drawn with weight = count^exponent, count being how often its
 * value occurs and the exponent configurable. With exponent=2.0 (default) this yields quadratic
 * scaling — biologically motivated by tandem repeat instability where the probability of deletion
 * through misalignment grows as O(N²) with repeat count.
 * <p>
 * The thermodynamic cost system (value-dependent POKE costs) provides the counterweight to
 * genome bloat from duplication. This plugin provides the matching variation: the redundant blocks
 * duplication and detour insertion leave behind are the ones that can be taken away again.
 * <p>
 * <strong>What it records:</strong> an applied deletion reports itself on the newborn as a
 * {@link MutationRecord} of kind {@code "deletion"}. Its cells are the label cell and every cleared
 * cell, in the order they are cleared; the old value is the removed molecule, the new value the
 * empty cell. Its one parameter is how often the chosen label's hash occurs in the genome, the
 * weight that made the deletion choose this label and the one thing the child no longer shows,
 * because the label is gone. A run that finds no candidate label deletes nothing and records nothing.
 * <p>
 * <strong>Thread Safety:</strong> Not thread-safe. Runs in the sequential post-Execute phase of
 * {@code Simulation.tick()}.
 *
 * @see org.evochora.runtime.spi.IBirthHandler
 * @see GeneDuplicationPlugin
 */
public class GeneDeletionPlugin implements IBirthHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GeneDeletionPlugin.class);

    /** The kind this plugin reports its deletions under. */
    private static final String MUTATION_KIND = "deletion";

    private final Random random;
    private final double deletionRate;
    private final double countExponent;
    private final int minLabelCount;

    // Reusable collections (cleared before each use)
    private final IntArrayList labelFlatIndices = new IntArrayList();
    private final IntArrayList labelHashes = new IntArrayList();
    private final Int2IntOpenHashMap hashCounts = new Int2IntOpenHashMap();

    /** Collects the record of a deletion; reused so that a birth allocates only the record itself. */
    private final MutationRecord.Builder recordBuilder = new MutationRecord.Builder();

    /**
     * Creates a gene deletion plugin from configuration.
     *
     * @param randomProvider Source of randomness.
     * @param config Configuration containing deletionRate, countExponent and minLabelCount; each
     *               key is mandatory, and a missing or malformed one is reported by name.
     */
    public GeneDeletionPlugin(IRandomProvider randomProvider, com.typesafe.config.Config config) {
        this.random = randomProvider.asJavaRandom();
        this.deletionRate = config.getDouble("deletionRate");
        this.countExponent = config.getDouble("countExponent");
        this.minLabelCount = config.getInt("minLabelCount");
        if (deletionRate < 0.0 || deletionRate > 1.0) {
            throw new IllegalArgumentException("deletionRate must be in [0.0, 1.0], got: " + deletionRate);
        }
        if (countExponent < 0.0) {
            throw new IllegalArgumentException("countExponent must be non-negative, got: " + countExponent);
        }
        if (minLabelCount < 1) {
            throw new IllegalArgumentException("minLabelCount must be at least 1, got: " + minLabelCount);
        }
    }

    /**
     * Convenience constructor for tests.
     *
     * @param randomProvider Source of randomness.
     * @param deletionRate Probability of deletion per newborn (0.0 to 1.0).
     * @param countExponent Exponent for duplicate label weighting.
     * @param minLabelCount How often a label's value must occur in the body for its blocks to be
     *                      candidates; 1 makes every label a candidate.
     */
    GeneDeletionPlugin(IRandomProvider randomProvider, double deletionRate, double countExponent, int minLabelCount) {
        this.random = randomProvider.asJavaRandom();
        this.deletionRate = deletionRate;
        this.countExponent = countExponent;
        this.minLabelCount = minLabelCount;
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
     * Collects all LABEL molecules owned by the child, keeps those whose value occurs at least
     * {@code minLabelCount} times as candidates, selects one of them using weighted reservoir
     * sampling (weight = hashCount^countExponent), then walks in DV direction deleting all
     * molecules until hitting the next LABEL, STRUCTURE, or a foreign molecule. A deletion that is
     * applied is recorded on the child.
     *
     * @param child The newborn organism.
     * @param env The simulation environment.
     */
    void delete(Organism child, Environment env) {
        int childId = child.getId();
        if (env.countCellsOwnedBy(childId) == 0) {
            LOG.debug("tick={} Organism {} gene deletion: no owned cells", child.getBirthTick(), childId);
            return;
        }

        // --- Phase 1: Collect all labels and count hash frequencies ---
        labelFlatIndices.clear();
        labelHashes.clear();
        hashCounts.clear();

        // The visit runs in flat-index order: the choice below must not depend on write history
        env.visitCellsOwnedBy(childId, cell -> {
            int moleculeInt = cell.moleculeInt();
            if ((moleculeInt & Config.TYPE_MASK) == Config.TYPE_LABEL) {
                int hash = moleculeInt & Config.VALUE_MASK;
                labelFlatIndices.add(env.properties.toFlatIndex(cell.coordinate()));
                labelHashes.add(hash);
                hashCounts.addTo(hash, 1);
            }
        });

        if (labelFlatIndices.isEmpty()) {
            LOG.debug("tick={} Organism {} selected for deletion but has no labels", child.getBirthTick(), childId);
            return;
        }

        // --- Phase 2: Weighted reservoir sampling over the candidates ---
        // A label whose value the body holds fewer than minLabelCount times is passed over, so
        // that a deletion takes away only what the body still carries elsewhere.
        double totalWeight = 0.0;
        int selectedIdx = -1;

        for (int i = 0; i < labelFlatIndices.size(); i++) {
            int count = hashCounts.get(labelHashes.getInt(i));
            if (count < minLabelCount) {
                continue;
            }
            double weight = Math.pow(count, countExponent);
            totalWeight += weight;
            if (random.nextDouble() * totalWeight < weight) {
                selectedIdx = i;
            }
        }

        if (selectedIdx < 0) {
            LOG.debug("tick={} Organism {} selected for deletion but has no label occurring at least {} times",
                    child.getBirthTick(), childId, minLabelCount);
            return;
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

        // How often the chosen hash occurs is what made this label the one deleted, and it is
        // unreadable in the child afterwards, because the label is gone
        recordBuilder.start(getClass().getName(), MUTATION_KIND, dv)
                .param(hashCounts.get(labelHashes.getInt(selectedIdx)));

        // Delete the label itself
        recordBuilder.cell(selectedFlatIndex, env.getMoleculeIntAt(pos), 0);
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
                break; // next block boundary
            }
            if (type == Config.TYPE_STRUCTURE) {
                break; // shell boundary
            }

            if (!mol.isEmpty()) {
                recordBuilder.cell(env.properties.toFlatIndex(pos), mol.toInt(), 0);
                env.setMolecule(new Molecule(Config.TYPE_CODE, 0), 0, pos);
                deletedCount++;
            }
        }

        child.recordBirthMutation(recordBuilder.build());

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
