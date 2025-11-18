package org.evochora.datapipeline.api.resources.database.dto;

/**
 * View model for a register value used by organism debugging APIs.
 * <p>
 * A register can either hold a molecule-encoded integer or a vector.
 */
public final class RegisterValueView {

    public enum Kind {
        MOLECULE,
        VECTOR
    }

    public final Kind kind;

    // For kind == MOLECULE
    public final Integer raw;      // full int32 from register
    public final Integer typeId;   // molecule type id (Config.TYPE_*)
    public final String type;      // human-readable type name
    public final Integer value;    // decoded signed value

    // For kind == VECTOR
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

    public static RegisterValueView molecule(int raw,
                                             int typeId,
                                             String type,
                                             int value) {
        return new RegisterValueView(Kind.MOLECULE, raw, typeId, type, value, null);
    }

    public static RegisterValueView vector(int[] vector) {
        return new RegisterValueView(Kind.VECTOR, null, null, null, null, vector);
    }
}


