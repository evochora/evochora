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
 * probability, selects a LABEL molecule and deletes the block it opens, along the organism's
 * direction vector. What it must not do is leave a cliff behind: an instruction pointer that runs
 * into the emptied cells, into the middle of an instruction or out of the genome.
 * <p>
 * <strong>Where a block begins.</strong> Only at a label that is an entry point in the machine's
 * reading frame ({@link GenomeFrame}); a LABEL molecule standing in another instruction's operand
 * list is that instruction's argument. And only where execution does not run into the label on
 * its own ({@link GenomeFlow#reachedByFallThrough}): the label stands at the beginning of the
 * newborn's extent on its scan line or behind an instruction that never falls through and that no
 * conditional can skip. Code before a label it runs into would run into the emptied cells after
 * the deletion. The newborn's initial position counts like such code: a label it runs into, and a
 * block it lies in, are not deleted. What enters the block is then only a jump to its label, and
 * that jump finds the label the body carries a second time.
 * <p>
 * <strong>Where a block ends.</strong> Before the next LABEL molecule, whatever its role and its
 * owner, because a jump can land there; otherwise at the end of the newborn's extent on the scan
 * line. Cells at the end of that stretch that are part of no instruction in the reading frame —
 * padding, a shell, data behind the code — are left standing, whatever their type, so that the
 * deletion takes the code of the block and not what surrounds it. Within the stretch it clears
 * every cell the newborn owns; a cell another organism owns or no one owns is left standing and
 * passed over, because it is not part of the newborn's genome.
 * <p>
 * <strong>Which labels are candidates:</strong> only those whose value the newborn's body holds at
 * least {@code minLabelCount} times among its LABEL cells that are entry points. With a minimum of
 * two the deletion removes only a block of which the body carries a second one under the same label
 * value, so that the references to the removed label find that one — the counterpart of
 * duplication, which leaves a second block under the same label value. The two need not be equal:
 * point mutations and insertions let duplicates diverge, and which of them survives is left to
 * selection. A label that names a place for the data pointer is a candidate like any other; the
 * references to it then find the second place. A label insertion leaves no value twice: the label
 * it writes takes over the value of a block's label, and that label is renamed, so both values
 * occur once and neither is drawn from. A label occurring fewer times is not drawn from at all;
 * {@code minLabelCount = 1} makes every label a candidate, unique blocks included.
 * <p>
 * Among the candidates a label is drawn with weight = count^exponent, count being how often its
 * value occurs and the exponent configurable. An exponent of two yields quadratic scaling —
 * biologically motivated by tandem repeat instability where the probability of deletion through
 * misalignment grows as O(N²) with repeat count.
 * <p>
 * The thermodynamic cost system (value-dependent POKE costs) provides the counterweight to
 * genome bloat from duplication. This plugin provides the matching variation: the redundant blocks
 * duplication leaves behind are the ones that can be taken away again.
 * <p>
 * <strong>Performance:</strong> The scan lines of the genome ({@link GenomeScanLines}) are built
 * for every newborn the deletion rate selects, and the reading frame on top of them for every such
 * newborn that carries a label, before the candidates are known, because which labels are
 * candidates is read from it. Each candidate costs a walk back to the code before its label and a
 * walk along its block. Beyond what building the scan lines and the frame allocates, a deletion
 * allocates the copies of the newborn's direction vector and initial position and, when it is
 * applied, the {@link MutationRecord}; the debug output allocates nothing while debug logging is
 * off.
 * <p>
 * <strong>What it records:</strong> an applied deletion reports itself on the newborn as a
 * {@link MutationRecord} of kind {@code "deletion"}. Its cells are the label cell and every cleared
 * cell, in the order they are cleared; the old value is the removed molecule, the new value the
 * empty cell. Its one parameter is how often the chosen label's hash occurs among the entry points
 * of the genome, the weight that made the deletion choose this label and the one thing the child
 * no longer shows, because the label is gone. A run that finds no candidate label deletes nothing
 * and records nothing.
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

    /** What a cleared cell holds. */
    private static final Molecule EMPTY_CELL = new Molecule(Config.TYPE_CODE, 0);

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

    /** The reading frame of the newborn, rebuilt for every deletion. */
    private final GenomeFrame frame = new GenomeFrame();

    /** The scan lines of the newborn's genome, rebuilt for every deletion. */
    private final GenomeScanLines scanLines = new GenomeScanLines();

    /** Answers whether execution runs into a label on its own, on top of the frame. */
    private final GenomeFlow flow = new GenomeFlow();

    /** Coordinates of the label being looked at while the candidates are collected and checked. */
    private int[] labelCoord;

    /** Coordinates of the cell a walk along a block stands on. */
    private int[] walkCoord;

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
     * Collects the LABEL molecules owned by the child that are entry points of its reading frame,
     * keeps as candidates those whose value occurs at least {@code minLabelCount} times among them
     * and whose block can be removed without leaving a cliff, selects one of them using weighted
     * reservoir sampling (weight = hashCount^countExponent), and clears the child's cells of its
     * block. A deletion that is applied is recorded on the child.
     *
     * @param child The newborn organism.
     * @param env The simulation environment.
     */
    void delete(Organism child, Environment env) {
        int childId = child.getId();
        if (env.countCellsOwnedBy(childId) == 0) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("tick={} Organism {} gene deletion: no owned cells", child.getBirthTick(), childId);
            }
            return;
        }

        int[] dv = child.getDv();
        scanLines.build(env, childId, dv, child.getInitialPosition());
        int dvDim = scanLines.dvDim();
        if (dvDim == -1) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("tick={} Organism {} gene deletion: degenerate DV", child.getBirthTick(), childId);
            }
            return;
        }
        int dims = env.properties.getDimensions();
        if (labelCoord == null || labelCoord.length != dims) {
            labelCoord = new int[dims];
            walkCoord = new int[dims];
        }

        // --- Phase 1: Collect the labels that are entry points and count hash frequencies ---
        // The scan lines list the labels in flat-index order: the choice below must not depend on
        // write history
        if (scanLines.labelCount() == 0) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("tick={} Organism {} selected for deletion but has no labels", child.getBirthTick(), childId);
            }
            return;
        }

        frame.build(env, scanLines);
        collectEntryPoints(env);

        if (labelFlatIndices.isEmpty()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("tick={} Organism {} selected for deletion but has no label that is an entry point",
                        child.getBirthTick(), childId);
            }
            return;
        }

        // --- Phase 2: Weighted reservoir sampling over the candidates ---
        // A label whose value the body holds fewer than minLabelCount times is passed over, so
        // that a deletion takes away only what the body still carries elsewhere, and so is a
        // label whose block cannot be removed without a cliff.
        double totalWeight = 0.0;
        int selectedIdx = -1;
        int selectedLength = 0;

        for (int i = 0; i < labelFlatIndices.size(); i++) {
            int count = hashCounts.get(labelHashes.getInt(i));
            if (count < minLabelCount) {
                continue;
            }
            int length = removableBlockLength(env, labelFlatIndices.getInt(i));
            if (length == 0) {
                continue;
            }
            // StrictMath gives the same result on every platform, so the selection, and with it
            // the run, does not depend on the machine.
            double weight = StrictMath.pow(count, countExponent);
            totalWeight += weight;
            if (random.nextDouble() * totalWeight < weight) {
                selectedIdx = i;
                selectedLength = length;
            }
        }

        if (selectedIdx < 0) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("tick={} Organism {} selected for deletion but has no label occurring at least {} times "
                        + "whose block can be removed", child.getBirthTick(), childId, minLabelCount);
            }
            return;
        }

        // --- Phase 3: Delete the block ---
        int selectedFlatIndex = labelFlatIndices.getInt(selectedIdx);

        // How often the chosen hash occurs is what made this label the one deleted, and it is
        // unreadable in the child afterwards, because the label is gone
        recordBuilder.start(getClass().getName(), MUTATION_KIND, dv)
                .param(hashCounts.get(labelHashes.getInt(selectedIdx)));

        env.properties.flatIndexToCoordinates(selectedFlatIndex, walkCoord);
        int deletedCount = 0;
        for (int k = 0; k < selectedLength; k++) {
            if (k > 0) {
                walkCoord[dvDim] = env.properties.stepAlongAxis(walkCoord[dvDim], dvDim, scanLines.step());
            }
            int moleculeInt = env.getMoleculeIntAt(walkCoord);
            // An empty cell holds nothing to remove, and a cell the child does not own is not
            // part of its genome
            if (moleculeInt == 0 || env.getOwnerId(walkCoord) != childId) {
                continue;
            }
            recordBuilder.cell(env.properties.toFlatIndex(walkCoord), moleculeInt, 0);
            env.setMolecule(EMPTY_CELL, 0, walkCoord);
            deletedCount++;
        }

        child.recordBirthMutation(recordBuilder.build());

        if (LOG.isDebugEnabled()) {
            int[] labelPos = env.properties.flatIndexToCoordinates(selectedFlatIndex);
            LOG.debug("tick={} Organism {} gene deletion: removed {} molecules from label hash {} at {}",
                    child.getBirthTick(), childId, deletedCount, labelHashes.getInt(selectedIdx), Arrays.toString(labelPos));
        }
    }

    /**
     * Collects of the newborn's labels those the frame reads as entry points, in flat-index order,
     * and counts how often each value occurs among them.
     *
     * @param env The simulation environment.
     */
    private void collectEntryPoints(Environment env) {
        labelFlatIndices.clear();
        labelHashes.clear();
        hashCounts.clear();
        for (int i = 0; i < scanLines.labelCount(); i++) {
            int flatIndex = scanLines.labelFlatIndex(i);
            if (frame.slot(flatIndex) != GenomeFrame.Slot.NONE) {
                continue;
            }
            env.properties.flatIndexToCoordinates(flatIndex, labelCoord);
            int hash = env.getMoleculeIntAt(labelCoord) & Config.VALUE_MASK;
            labelFlatIndices.add(flatIndex);
            labelHashes.add(hash);
            hashCounts.addTo(hash, 1);
        }
    }

    /**
     * Determines how many cells, from a label along the direction vector, a deletion of its block
     * spans, or that the block cannot be removed without leaving a cliff.
     * <p> The block cannot be removed if execution runs into the label without a jump to it — the
     * newborn's initial position included — or if the initial position lies within the block.
     * Otherwise it spans the label and every cell up to the next LABEL molecule or the end of the
     * newborn's extent on the scan line, without the cells at its end that are part of no
     * instruction.
     *
     * @param env The simulation environment.
     * @param labelFlatIndex The flat index of a label the frame reads as an entry point, one of the
     *                       newborn's own labels, which lies within the extent of its scan line.
     * @return The number of cells the deletion spans, at least one for the label itself, or
     *         {@code 0} if the block cannot be removed.
     */
    private int removableBlockLength(Environment env, int labelFlatIndex) {
        env.properties.flatIndexToCoordinates(labelFlatIndex, labelCoord);
        if (flow.reachedByFallThrough(env, frame, labelCoord)) {
            return 0;
        }

        int dvDim = scanLines.dvDim();
        int extent = scanLines.cellsFrom(labelCoord);
        System.arraycopy(labelCoord, 0, walkCoord, 0, labelCoord.length);
        int length = 1;
        int initialPositionOffset = -1;
        for (int walked = 1; walked < extent; walked++) {
            walkCoord[dvDim] = env.properties.stepAlongAxis(walkCoord[dvDim], dvDim, scanLines.step());
            if ((env.getMoleculeIntAt(walkCoord) & Config.TYPE_MASK) == Config.TYPE_LABEL) {
                break;
            }
            int flatIndex = env.properties.toFlatIndex(walkCoord);
            if (frame.isInitialPosition(flatIndex)) {
                initialPositionOffset = walked;
            }
            if (frame.slot(flatIndex) != GenomeFrame.Slot.NONE) {
                length = walked + 1;
            }
        }
        return initialPositionOffset >= 0 && initialPositionOffset < length ? 0 : length;
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
