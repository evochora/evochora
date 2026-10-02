package org.evochora.compiler.backend.emit;

import org.evochora.compiler.api.PlacedMolecule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;

/**
 * The identity of a compiled program: a digest of what the compiler puts into the world, its
 * machine code and its initial objects, and of the preprocessor flags it was compiled with. Two
 * programs share an identity exactly when they place the same values at the same coordinates
 * under the same flags; names, comments and the source text itself do not enter. The flags
 * enter because two variants that compile to the same code would otherwise share one artifact,
 * whose folded branches and notes describe only one of them.
 *
 * <p>The digest is SHA-256 over a stream of 32-bit big-endian integers and the bytes of the flag
 * names, rendered as lower-case hex and truncated to its first 16 characters (64 bits). The
 * stream is length-prefixed, so no two different programs produce the same stream:</p>
 * <ol>
 *   <li>the number of dimensions, the length of the coordinates (0 for a program that places
 *       nothing);</li>
 *   <li>the number of code entries, then for each entry in coordinate order the coordinate's
 *       components followed by the cell value;</li>
 *   <li>the number of initial objects, then for each object in coordinate order the coordinate's
 *       components followed by the {@link PlacedMolecule}'s type, value and marker;</li>
 *   <li>the number of flags, then for each flag in alphabetical order of its upper-cased name:
 *       the number of bytes of that name in UTF-8 followed by those bytes, then 1 if the flag has
 *       a value and 0 if not, then the value (0 for a flag without one).</li>
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
     * Computes the identity of a program from what it puts into the world and the flags it was
     * compiled with.
     *
     * @param machineCode    The cell value at each coordinate the program's code occupies.
     * @param initialObjects The molecule at each coordinate the program places as an object.
     * @param flags          The preprocessor flags of the compilation, name to value or empty for
     *                       a flag without one. Names are upper-cased for the digest, so they
     *                       must differ in more than case, as the compiler options ensure.
     * @return The identity: 16 lower-case hex characters.
     */
    public static String of(Map<int[], Integer> machineCode, Map<int[], PlacedMolecule> initialObjects,
                            Map<String, OptionalInt> flags) {
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

        List<Map.Entry<String, OptionalInt>> sortedFlags = new ArrayList<>(flags.size());
        flags.forEach((name, value) -> sortedFlags.add(Map.entry(name.toUpperCase(Locale.ROOT), value)));
        sortedFlags.sort(Map.Entry.comparingByKey());
        update(digest, sortedFlags.size());
        for (Map.Entry<String, OptionalInt> flag : sortedFlags) {
            byte[] name = flag.getKey().getBytes(StandardCharsets.UTF_8);
            update(digest, name.length);
            digest.update(name);
            OptionalInt value = flag.getValue();
            update(digest, value.isPresent() ? 1 : 0);
            update(digest, value.orElse(0));
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
