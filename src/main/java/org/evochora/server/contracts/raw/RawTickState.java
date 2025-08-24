package org.evochora.server.contracts.raw;

import org.evochora.server.contracts.IQueueMessage;

import java.io.Serializable;
import java.util.List;

/**
 * Top-Level-Container für alle rohen Zustandsdaten eines einzelnen Ticks.
 */
public record RawTickState(
        long tickNumber,
        List<RawOrganismState> organisms,
        List<RawCellState> cells
) implements IQueueMessage, Serializable {}