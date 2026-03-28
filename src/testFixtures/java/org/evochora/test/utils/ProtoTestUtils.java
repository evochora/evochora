package org.evochora.test.utils;

import org.evochora.datapipeline.api.contracts.RegisterValue;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.runtime.isa.RegisterBank;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared test utilities for building protobuf register state.
 * Iterates {@link RegisterBank#values()} to ensure the flat register list
 * always matches the runtime's bank order and counts.
 */
public final class ProtoTestUtils {

    private ProtoTestUtils() {}

    /**
     * Builds a flat RegisterValue list in {@link RegisterBank} enum order.
     * Populates DR, LR, PDR, and FDR slots from the provided arrays.
     * All other banks are filled with defaults (scalar 0 or empty vector).
     *
     * @param drScalars  DR scalar values (nullable, defaults to 0).
     * @param lrVectors  LR vector values (nullable, defaults to empty vector).
     * @param pdrScalars PDR scalar values (nullable, defaults to 0).
     * @param fdrScalars FDR scalar values (nullable, defaults to 0).
     * @return flat RegisterValue list with {@link RegisterBank#TOTAL_REGISTER_COUNT} entries.
     */
    public static List<RegisterValue> buildFlatRegisters(
            int[] drScalars, int[][] lrVectors, int[] pdrScalars, int[] fdrScalars) {
        List<RegisterValue> result = new ArrayList<>(RegisterBank.TOTAL_REGISTER_COUNT);
        for (RegisterBank bank : RegisterBank.values()) {
            for (int i = 0; i < bank.count; i++) {
                if (bank.isLocation) {
                    Vector.Builder vb = Vector.newBuilder();
                    if (bank == RegisterBank.LR && lrVectors != null && i < lrVectors.length && lrVectors[i] != null) {
                        for (int c : lrVectors[i]) vb.addComponents(c);
                    }
                    result.add(RegisterValue.newBuilder().setVector(vb.build()).build());
                } else {
                    int val = 0;
                    if (bank == RegisterBank.DR && drScalars != null && i < drScalars.length) val = drScalars[i];
                    else if (bank == RegisterBank.PDR && pdrScalars != null && i < pdrScalars.length) val = pdrScalars[i];
                    else if (bank == RegisterBank.FDR && fdrScalars != null && i < fdrScalars.length) val = fdrScalars[i];
                    result.add(RegisterValue.newBuilder().setScalar(val).build());
                }
            }
        }
        return result;
    }
}
