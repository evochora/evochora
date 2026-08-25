package org.evochora.test.utils;

import com.google.protobuf.ByteString;

import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.ProcFrame;
import org.evochora.datapipeline.api.contracts.RegisterValue;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.runtime.Config;
import org.evochora.runtime.internal.services.SeededRandomProvider;
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

    /**
     * Builds an organism state carrying everything a running simulation would have written: a full
     * register set, the build's data pointers, and the coordinates the caller supplies.
     * <p>
     * Fixtures are derived from the runtime's own structure declarations rather than described by
     * hand, so adding a register bank or changing the data pointer count keeps every test that builds
     * on this correct. Tests that need a defective state start here and break exactly one part of it.
     *
     * @param id the organism id
     * @param energy the organism's energy
     * @param position the coordinate used for IP, initial position and both data pointers
     * @return a builder holding a complete, well-formed organism state
     */
    public static OrganismState.Builder wellFormedOrganism(int id, int energy, int... position) {
        Vector at = vector(position);
        Vector direction = unitVector(position.length);

        OrganismState.Builder builder = OrganismState.newBuilder()
            .setOrganismId(id)
            .setBirthTick(0)
            .setEnergy(energy)
            .setIp(at)
            .setDv(direction)
            .setInitialPosition(at)
            .addAllRegisters(buildFlatRegisters(null, null, null, null))
            .setIsDead(false);

        for (int i = 0; i < Config.NUM_DATA_POINTERS; i++) {
            builder.addDataPointers(at);
        }
        return builder;
    }

    /**
     * Builds a call frame whose saved-register snapshot has the size the runtime expects.
     * An absent snapshot is expressed by adding no saved registers at all, not by a partial one.
     *
     * @param labelHash the procedure's label hash
     * @param position the coordinate used for both the return and the call address
     * @return a builder holding a complete call frame
     */
    public static ProcFrame.Builder wellFormedCallFrame(int labelHash, int... position) {
        Vector at = vector(position);
        ProcFrame.Builder frame = ProcFrame.newBuilder()
            .setLabelHash(labelHash)
            .setAbsoluteReturnIp(at)
            .setAbsoluteCallIp(at);

        for (RegisterBank bank : RegisterBank.allSavedOnCall()) {
            for (int i = 0; i < bank.count; i++) {
                frame.addSavedRegisters(bank.isLocation
                        ? RegisterValue.newBuilder().setVector(vector(new int[position.length])).build()
                        : RegisterValue.newBuilder().setScalar(0).build());
            }
        }
        return frame;
    }

    /** The RNG state a snapshot carries; without it a run cannot be continued deterministically. */
    public static ByteString rngState(long seed) {
        return ByteString.copyFrom(new SeededRandomProvider(seed).saveState());
    }

    private static Vector vector(int... components) {
        Vector.Builder builder = Vector.newBuilder();
        for (int c : components) {
            builder.addComponents(c);
        }
        return builder.build();
    }

    /** A unit vector along the first axis, the direction a fresh organism starts with. */
    private static Vector unitVector(int dimensions) {
        int[] components = new int[dimensions];
        if (dimensions > 0) {
            components[0] = 1;
        }
        return vector(components);
    }
}
