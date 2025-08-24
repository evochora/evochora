package org.evochora.server.contracts.raw;

import org.evochora.runtime.model.Organism;

import java.util.Deque;
import java.util.List;

/**
 * Contains the complete, raw state of a single organism for a specific tick.
 * This record is designed for speed and minimal overhead. All registers and stacks
 * store the direct runtime objects (e.g., Integer, int[], Deque) without any
 * formatting or conversion.
 *
 * @param id The unique ID of the organism.
 * @param programId The ID of the program artifact this organism was created from.
 * @param parentId The ID of the organism's parent, if any.
 * @param birthTick The tick number when this organism was created.
 * @param energy The current energy level of the organism.
 * @param ip The instruction pointer (position vector).
 * @param dv The direction vector.
 * @param dps The direction pointers.
 * @param activeDpIndex The index of the currently active direction pointer.
 * @param drs The data registers.
 * @param prs The procedure registers.
 * @param fprs The free procedure registers.
 * @param lrs The location registers.
 * @param dataStack The data stack.
 * @param locationStack The location stack.
 * @param callStack The call stack, containing procedure frames.
 */
public record RawOrganismState(
        int id,
        String programId,
        Integer parentId,
        long birthTick,
        long energy,
        int[] ip,
        int[] dv,
        List<int[]> dps,
        int activeDpIndex,
        List<Object> drs,
        List<Object> prs,
        List<Object> fprs,
        List<Object> lrs,
        Deque<Object> dataStack,
        Deque<int[]> locationStack,
        Deque<Organism.ProcFrame> callStack
) {}
