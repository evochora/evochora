package org.evochora.runtime.model;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.evochora.runtime.Config;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes genome hash for organisms based on their owned molecules.
 * <p>
 * The genome hash uniquely identifies an organism's genetic material independent of
 * its absolute position in the environment. It includes every molecule the organism owns
 * except STATE. A STATE cell is what an organism wrote for itself while running: the
 * environment stores a value written with marker register 0 as STATE, and its content varies
 * between copies of the same genome, because the copy loop carries the parent's state cells
 * into the child as they stand at copy time. Including them would make every birth look like a
 * mutation.
 * <p>
 * LABEL and LABELREF values are normalized before hashing: all values are XOR-ed with
 * the value of the LABEL molecule at the smallest relative position (the "anchor label"). This
 * makes the hash invariant to uniform label namespace rewriting (as performed by
 * {@link org.evochora.runtime.worldgen.LabelRewritePlugin}) while still detecting
 * individual mutations to label or labelref values. The normalization is correct because
 * {@code (A ^ M) ^ (B ^ M) = A ^ B} — a uniform XOR mask cancels out in pairwise
 * differences.
 * <p>
 * The hash is computed using SHA-256 over sorted (relative position, molecule value) pairs,
 * with the first 8 bytes converted to a long for compact storage.
 */
public final class GenomeHasher {

    private GenomeHasher() {
        // Utility class - no instantiation
    }

    /**
     * Compares two entries by their relative position (first {@code dims} elements) in lexicographic order.
     *
     * @return {@code true} if {@code a} is lexicographically before {@code b}.
     */
    private static boolean lexicographicallySmaller(long[] a, long[] b, int dims) {
        for (int d = 0; d < dims; d++) {
            if (a[d] < b[d]) return true;
            if (a[d] > b[d]) return false;
        }
        return false;
    }

    /**
     * Computes the genome hash for cells owned by the given organism.
     * <p>
     * The genome includes all molecule types except STATE, the type an organism's own runtime
     * memory is stored as: CODE, DATA, LABEL, LABELREF, REGISTER, STRUCTURE, ENERGY.
     * <p>
     * Molecules are sorted by their relative position (to initialPosition) in lexicographic
     * order before hashing, ensuring the same genome produces the same hash regardless of
     * iteration order.
     * <p>
     * The relative position of a molecule is
     * {@link EnvironmentProperties#relativeOffset(int, int, int, boolean)}, the same rule every
     * consumer places a recorded cell by. A position computed differently would not be comparable
     * with the hash it accompanies: two bodies whose molecules sit at the same offsets have the
     * same hash, and a rule with the opposite tie-break would put a body spanning exactly half the
     * world on the other side of its origin.
     *
     * @param environment The environment containing the cells
     * @param organismId The organism ID whose cells to hash
     * @param initialPosition The organism's initial position (for relative coordinate calculation)
     * @return 64-bit hash of the genome, or 0L if no genome molecules found
     */
    public static long computeGenomeHash(Environment environment, int organismId, int[] initialPosition) {
        IntOpenHashSet ownedCells = environment.getCellsOwnedBy(organismId);
        if (ownedCells == null || ownedCells.isEmpty()) {
            return 0L;
        }

        int dims = initialPosition.length;
        int[] shape = environment.getShape();
        boolean isToroidal = environment.getProperties().isToroidal();
        List<long[]> genomeMolecules = new ArrayList<>();

        // Track anchor label: the LABEL at the smallest RELATIVE position (lexicographic).
        // Using the relative position instead of the cell's index ensures the same anchor is
        // chosen regardless of absolute placement in toroidal worlds.
        int anchorLabelValue = -1;
        int anchorEntryIndex = -1;

        // Collect all non-STATE molecules with their relative positions. The set iterates in an
        // order that follows the environment's layout indices, and so its memory layout; the
        // sort by relative position below removes that order, and it is total because two cells
        // never share a position, so the hash cannot depend on the layout.
        for (int layoutIndex : ownedCells) {
            int moleculeInt = environment.getMoleculeInt(layoutIndex);
            int type = moleculeInt & Config.TYPE_MASK;

            // Skip STATE molecules - they hold what the organism wrote for itself while running
            if (type == Config.TYPE_STATE) {
                continue;
            }

            int[] absCoord = environment.getCoordinateFromIndex(layoutIndex);

            // Create entry: [relPos0, relPos1, ..., relPosN, moleculeValue]
            long[] entry = new long[dims + 1];
            for (int d = 0; d < dims; d++) {
                entry[d] = EnvironmentProperties.relativeOffset(
                    absCoord[d], initialPosition[d], shape[d], isToroidal);
            }
            entry[dims] = moleculeInt;
            genomeMolecules.add(entry);

            // Track anchor label (smallest relative position among LABELs)
            if (type == Config.TYPE_LABEL) {
                if (anchorEntryIndex == -1 || lexicographicallySmaller(entry, genomeMolecules.get(anchorEntryIndex), dims)) {
                    anchorEntryIndex = genomeMolecules.size() - 1;
                    anchorLabelValue = moleculeInt & Config.VALUE_MASK;
                }
            }
        }

        // Normalize LABEL/LABELREF values by XOR-ing with the anchor label value.
        // This makes the hash invariant to uniform namespace rewriting (birth XOR mask)
        // while still detecting individual mutations.
        if (anchorLabelValue != -1) {
            for (long[] entry : genomeMolecules) {
                int moleculeInt = (int) entry[dims];
                int type = moleculeInt & Config.TYPE_MASK;
                if (type == Config.TYPE_LABEL || type == Config.TYPE_LABELREF) {
                    int normalizedValue = (moleculeInt & Config.VALUE_MASK) ^ anchorLabelValue;
                    entry[dims] = (moleculeInt & ~Config.VALUE_MASK) | normalizedValue;
                }
            }
        }

        if (genomeMolecules.isEmpty()) {
            return 0L;
        }

        // Sort by relative position (lexicographic), then by molecule value
        genomeMolecules.sort((a, b) -> {
            for (int d = 0; d < dims; d++) {
                int cmp = Long.compare(a[d], b[d]);
                if (cmp != 0) return cmp;
            }
            return Long.compare(a[dims], b[dims]);
        });

        // Compute SHA-256 hash
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer buffer = ByteBuffer.allocate((dims + 1) * Long.BYTES);

            for (long[] entry : genomeMolecules) {
                buffer.clear();
                for (long val : entry) {
                    buffer.putLong(val);
                }
                digest.update(buffer.array());
            }

            byte[] hash = digest.digest();
            // Take first 8 bytes as long (big-endian)
            return ByteBuffer.wrap(hash, 0, Long.BYTES).getLong();

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
