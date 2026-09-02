package org.evochora.datapipeline.api.resources.database.dto;

/**
 * View model for a register value used by organism debugging APIs.
 * <p>
 * A register can either hold a molecule-encoded integer or a vector.
 */
public final class RegisterValueView {

    /** Which of the two mutually exclusive value shapes a view carries. */
    public enum Kind {
        /** The molecule fields are populated and {@code vector} is {@code null}. */
        MOLECULE,
        /** {@code vector} is populated and the molecule fields are {@code null}. */
        VECTOR
    }

    /** Selects which group of fields holds the value; never {@code null}. */
    public final Kind kind;

    // For kind == MOLECULE
    /** The packed register word with type, marker and value bits still combined. */
    public final Integer raw;      // full int32 from register
    /** Type component of {@link #raw}, already shifted into place so it compares against the {@code Config.TYPE_*} constants. */
    public final Integer typeId;   // molecule type id (Config.TYPE_*)
    /** Name registered for {@link #typeId}, or {@code "UNKNOWN"} for a type code no name is registered for. */
    public final String type;      // human-readable type name
    /** Sign-extended payload of {@link #raw} with the type and marker bits removed. */
    public final Integer value;    // decoded signed value

    // For kind == VECTOR
    /** Vector components in environment axis order, as many as the register holds. */
    public final int[] vector;

    private RegisterValueView(Kind kind,
                              Integer raw,
                              Integer typeId,
                              String type,
                              Integer value,
                              int[] vector) {
        this.kind = kind;
        this.raw = raw;
        this.typeId = typeId;
        this.type = type;
        this.value = value;
        this.vector = vector;
    }

    /**
     * Creates a view of a register holding a molecule, with the packed word kept alongside its
     * decoded parts so a caller can display either without decoding again.
     *
     * @param raw    The packed register word.
     * @param typeId Type component of {@code raw}, shifted into place.
     * @param type   Registered name of that type.
     * @param value  Sign-extended payload of {@code raw}.
     * @return A view whose {@link #kind} is {@link Kind#MOLECULE} and whose {@code vector} is {@code null}.
     */
    public static RegisterValueView molecule(int raw,
                                             int typeId,
                                             String type,
                                             int value) {
        return new RegisterValueView(Kind.MOLECULE, raw, typeId, type, value, null);
    }

    /**
     * Creates a view of a register holding a coordinate vector. The array is stored as given,
     * not copied, so the caller must not modify it afterwards.
     *
     * @param vector The vector components in environment axis order.
     * @return A view whose {@link #kind} is {@link Kind#VECTOR} and whose molecule fields are {@code null}.
     */
    public static RegisterValueView vector(int[] vector) {
        return new RegisterValueView(Kind.VECTOR, null, null, null, null, vector);
    }
}


