package org.evochora.server.contracts.raw;

import java.io.Serializable;
import java.util.Map;

/**
 * Eine serialisierbare Repräsentation eines ProcFrame für die Speicherung des Rohzustands.
 */
public record SerializableProcFrame(
        String procName,
        int[] absoluteReturnIp,
        Object[] savedPrs,
        Object[] savedFprs,
        Map<Integer, Integer> fprBindings
) implements Serializable {}