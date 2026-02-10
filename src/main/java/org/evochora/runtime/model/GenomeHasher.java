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
 * its absolute position in the environment. It includes all molecule types except DATA,
 * which can be modified by the organism at runtime.
 * <p>
 * LABEL and LABELREF values are normalized before hashing: all values are XOR-ed with
 * the value of the LABEL molecule at the smallest flat index (the "anchor label"). This
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
     * Computes the genome hash for cells owned by the given organism.
     * <p>
     * The genome includes all molecule types except DATA:
     * CODE, LABEL, LABELREF, REGISTER, STRUCTURE, ENERGY.
     * <p>
     * Molecules are sorted by their relative position (to initialPosition) in lexicographic
     * order before hashing, ensuring the same genome produces the same hash regardless of
     * iteration order.
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

        // Track anchor label: the LABEL with the smallest flat index, used to normalize
        // LABEL/LABELREF values so that uniform XOR rewriting does not change the hash.
        int anchorLabelValue = -1;
        int anchorFlatIndex = Integer.MAX_VALUE;

        // Collect all non-DATA molecules with their relative positions
        for (int flatIndex : ownedCells) {
            int moleculeInt = environment.getMoleculeInt(flatIndex);
            int type = moleculeInt & Config.TYPE_MASK;

            // Skip DATA molecules - they can be modified by the organism at runtime
            if (type == Config.TYPE_DATA) {
                continue;
            }

            // Track anchor label (smallest flat index among LABELs)
            if (type == Config.TYPE_LABEL && flatIndex < anchorFlatIndex) {
                anchorFlatIndex = flatIndex;
                anchorLabelValue = moleculeInt & Config.VALUE_MASK;
            }

            int[] absCoord = environment.getCoordinateFromIndex(flatIndex);

            // Create entry: [relPos0, relPos1, ..., relPosN, moleculeValue]
            long[] entry = new long[dims + 1];
            for (int d = 0; d < dims; d++) {
                int diff = absCoord[d] - initialPosition[d];
                // In toroidal worlds, use shortest distance to ensure identical genomes
                // get the same hash regardless of world boundary wrapping
                if (isToroidal) {
                    int worldSize = shape[d];
                    if (diff > worldSize / 2) {
                        diff -= worldSize;
                    } else if (diff < -worldSize / 2) {
                        diff += worldSize;
                    }
                }
                entry[d] = diff;
            }
            entry[dims] = moleculeInt;
            genomeMolecules.add(entry);
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
