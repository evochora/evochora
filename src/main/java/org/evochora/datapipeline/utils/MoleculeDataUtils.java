package org.evochora.datapipeline.utils;

import org.evochora.runtime.Config;

/**
 * Utility class for manipulating packed molecule data.
 * <p>
 * This class serves as the canonical location for packing and unpacking the
 * {@code molecule_data} integer field defined in {@code tickdata_contracts.proto}.
 * It encapsulates the bitwise operations required to handle the 
 * {@code type}, {@code value}, and {@code marker} components packed into a single {@code int32}.
 * <p>
 * Constants for masks and shifts are defined in {@link org.evochora.runtime.Config}.
 */
public final class MoleculeDataUtils {

    private MoleculeDataUtils() {
        // Utility class
    }

    /**
     * Extracts the signed value from the packed molecule integer.
     * Handles 2's complement sign extension for the 16-bit value within the 32-bit container.
     * 
     * @param moleculeInt The packed molecule data
     * @return The signed integer value
     */
    public static int extractSignedValue(int moleculeInt) {
        int rawValue = moleculeInt & Config.VALUE_MASK;
        // Check sign bit of the value segment (e.g. bit 15 for 16-bit value)
        if ((rawValue & (1 << (Config.VALUE_BITS - 1))) != 0) {
            // Sign extend to full 32-bit int by filling upper bits with 1s
            rawValue |= ~((1 << Config.VALUE_BITS) - 1);
        }
        return rawValue;
    }
}
