package org.evochora.cli.rendering.frame;

import java.util.Arrays;

import org.evochora.runtime.Config;
import org.evochora.runtime.model.MoleculeTypeRegistry;

/**
 * The colour every molecule type is drawn in by the frame renderers.
 * <p>
 * Holds one colour per type registered in {@link MoleculeTypeRegistry} and one for a molecule
 * whose type the registry does not know. The colours match the web visualizer's palette, so a
 * rendered video and the visualizer show the same world in the same colours.
 * <p>
 * EMPTY and DEAD are not molecule types and are not held here: a cell holding no molecule and a
 * dead organism's marker are each renderer's own background decision. A lookup here therefore
 * never yields an empty-cell colour — an unregistered type is visible as UNKNOWN instead of
 * disappearing into the background.
 * <p>
 * The colours are addressed by a dense <em>slot</em>: the registry index of a registered type, or
 * {@link #unknownSlot()} for everything else. A renderer that aggregates cells per output pixel
 * sizes its count array with {@link #slotCount()} and votes per slot.
 * <p>
 * <strong>Thread Safety:</strong> Immutable after class initialization; all methods are read-only.
 */
public final class MoleculeTypeColors {

    /** RGB colour per slot: the registered types in registration order, then UNKNOWN. */
    private static final int[] COLORS = buildColors();

    /** Slot per raw type index; the unknown slot where the index belongs to no registered type. */
    private static final int[] SLOT_BY_RAW_INDEX = buildSlotByRawIndex();

    /**
     * Private constructor to prevent instantiation.
     */
    private MoleculeTypeColors() {
        throw new AssertionError("Utility class - cannot be instantiated");
    }

    /**
     * Builds the colour of every slot.
     *
     * @return RGB colours indexed by slot, the unknown colour last.
     */
    private static int[] buildColors() {
        int[] colors = new int[MoleculeTypeRegistry.typeCount() + 1];
        colors[MoleculeTypeRegistry.indexOf(Config.TYPE_CODE)] = 0x3c5078;       // blue-gray
        colors[MoleculeTypeRegistry.indexOf(Config.TYPE_DATA)] = 0x32323c;       // dark gray
        colors[MoleculeTypeRegistry.indexOf(Config.TYPE_ENERGY)] = 0xffe664;     // yellow
        colors[MoleculeTypeRegistry.indexOf(Config.TYPE_STRUCTURE)] = 0xff7878;  // red/pink
        colors[MoleculeTypeRegistry.indexOf(Config.TYPE_LABEL)] = 0xa0a0a8;      // light gray
        colors[MoleculeTypeRegistry.indexOf(Config.TYPE_LABELREF)] = 0xa0a0a8;   // light gray, as LABEL
        colors[MoleculeTypeRegistry.indexOf(Config.TYPE_REGISTER)] = 0x506080;   // medium blue-gray
        colors[MoleculeTypeRegistry.indexOf(Config.TYPE_STATE)] = 0x32323c;      // dark gray, as DATA
        colors[colors.length - 1] = 0xff00ff;                                    // magenta, unmistakable
        return colors;
    }

    /**
     * Builds the lookup from a molecule's raw type index to its slot.
     *
     * @return An array whose entry is the slot of that raw type index.
     */
    private static int[] buildSlotByRawIndex() {
        int highest = 0;
        for (int type : MoleculeTypeRegistry.orderedTypes()) {
            highest = Math.max(highest, rawIndex(type));
        }
        int[] slots = new int[highest + 1];
        Arrays.fill(slots, COLORS.length - 1);
        for (int type : MoleculeTypeRegistry.orderedTypes()) {
            slots[rawIndex(type)] = MoleculeTypeRegistry.indexOf(type);
        }
        return slots;
    }

    /**
     * Returns the position of a molecule type's bits within the packed molecule integer, shifted
     * down to a small index.
     *
     * @param type The shifted type constant.
     * @return The raw type index.
     */
    private static int rawIndex(int type) {
        return (type & Config.TYPE_MASK) >>> Config.TYPE_SHIFT;
    }

    /**
     * Returns the number of colour slots: one per registered molecule type, plus UNKNOWN.
     *
     * @return The exclusive upper bound of every slot this class returns.
     */
    public static int slotCount() {
        return COLORS.length;
    }

    /**
     * Returns the slot of molecules whose type is not registered.
     *
     * @return The UNKNOWN slot, the last one.
     */
    public static int unknownSlot() {
        return COLORS.length - 1;
    }

    /**
     * Returns the colour slot a molecule type is drawn from.
     *
     * @param moleculeType The shifted type constant, as it sits in a packed molecule.
     * @return The registry index of the type, or {@link #unknownSlot()} if it is not registered.
     */
    public static int slotOf(int moleculeType) {
        int index = rawIndex(moleculeType);
        return index < SLOT_BY_RAW_INDEX.length ? SLOT_BY_RAW_INDEX[index] : unknownSlot();
    }

    /**
     * Returns the colour of a slot.
     *
     * @param slot A slot in {@code 0 .. slotCount() - 1}.
     * @return The RGB colour of that slot.
     */
    public static int colorOfSlot(int slot) {
        return COLORS[slot];
    }

    /**
     * Returns the colour a molecule type is drawn in.
     *
     * @param moleculeType The shifted type constant, as it sits in a packed molecule.
     * @return The RGB colour of that type, or the UNKNOWN colour if it is not registered.
     */
    public static int colorOf(int moleculeType) {
        return COLORS[slotOf(moleculeType)];
    }
}
