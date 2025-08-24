package org.evochora.server.contracts.raw;

import java.io.Serializable;
import java.util.Deque;
import java.util.List;

/**
 * Enthält den kompletten, serialisierbaren Rohzustand eines Organismus
 * für einen einzelnen Tick. Ermöglicht die Wiederaufnahme der Simulation.
 */
public record RawOrganismState(
        // Metadaten
        int id,
        Integer parentId,
        long birthTick,
        String programId,
        int[] initialPosition,

        // Kernzustand
        int[] ip,
        int[] dv,
        List<int[]> dps,
        int activeDpIndex,
        int er,

        // Register & Stacks (als serialisierbare Typen)
        List<Object> drs,
        List<Object> prs,
        List<Object> fprs,
        List<Object> lrs,
        Deque<Object> dataStack,
        Deque<int[]> locationStack,
        Deque<SerializableProcFrame> callStack,

        // Per-Tick Zustandsflags
        boolean isDead,
        boolean instructionFailed,
        String failureReason,
        boolean skipIpAdvance,
        int[] ipBeforeFetch,
        int[] dvBeforeFetch
) implements Serializable {}