package org.evochora.compiler.backend.emit;

import org.evochora.compiler.api.PlacedMolecule;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * The identity of a compiled program: a digest of what the compiler puts into the world, its
 * machine code and its initial objects. Two programs share an identity exactly when they place
 * the same values at the same coordinates; how the source chose them (flags, names, comments,
 * the source text itself) does not enter.
 *
 * <p>The digest is SHA-256 over a stream of 32-bit big-endian integers, rendered as lower-case
 * hex and truncated to its first 16 characters (64 bits). The stream is length-prefixed, so no
 * two different programs produce the same stream:</p>
 * <ol>
 *   <li>the number of dimensions, the length of the coordinates (0 for a program that places
 *       nothing);</li>
 *   <li>the number of code entries, then for each entry in coordinate order the coordinate's
 *       components followed by the cell value;</li>
 *   <li>the number of initial objects, then for each object in coordinate order the coordinate's
 *       components followed by the {@link PlacedMolecule}'s type, value and marker.</li>
 * </ol>
 * <p>Coordinate order is the lexicographic order of {@link Arrays#compare(int[], int[])}; the
 * digest sorts the entries itself, so the iteration order of the maps it is given does not
 * matter. No Java {@code hashCode} enters the digest.</p>
 */
public final class ProgramIdentity {

    /** Number of hex characters of the digest that form the identity. */
    private static final int ID_LENGTH = 16;

    private ProgramIdentity() {
    }

    /**
     * Computes the identity of a program from what it puts into the world.
     *
     * @param machineCode    The cell value at each coordinate the program's code occupies.
     * @param initialObjects The molecule at each coordinate the program places as an object.
     * @return The identity: 16 lower-case hex characters.
     */
    public static String of(Map<int[], Integer> machineCode, Map<int[], PlacedMolecule> initialObjects) {
        MessageDigest digest = sha256();
        int dimensions = !machineCode.isEmpty() ? machineCode.keySet().iterator().next().length
                : !initialObjects.isEmpty() ? initialObjects.keySet().iterator().next().length
                : 0;
        update(digest, dimensions);

        update(digest, machineCode.size());
        for (Map.Entry<int[], Integer> entry : inCoordinateOrder(machineCode)) {
            updateCoordinate(digest, entry.getKey());
            update(digest, entry.getValue());
        }

        update(digest, initialObjects.size());
        for (Map.Entry<int[], PlacedMolecule> entry : inCoordinateOrder(initialObjects)) {
            updateCoordinate(digest, entry.getKey());
            PlacedMolecule molecule = entry.getValue();
            update(digest, molecule.type());
            update(digest, molecule.value());
            update(digest, molecule.marker());
        }

        return HexFormat.of().formatHex(digest.digest()).substring(0, ID_LENGTH);
    }

    private static <V> List<Map.Entry<int[], V>> inCoordinateOrder(Map<int[], V> map) {
        List<Map.Entry<int[], V>> entries = new ArrayList<>(map.entrySet());
        entries.sort((a, b) -> Arrays.compare(a.getKey(), b.getKey()));
        return entries;
    }

    private static void updateCoordinate(MessageDigest digest, int[] coordinate) {
        for (int component : coordinate) {
            update(digest, component);
        }
    }

    private static void update(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform is required to provide SHA-256
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
