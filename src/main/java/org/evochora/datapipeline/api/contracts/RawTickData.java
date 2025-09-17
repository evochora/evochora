package org.evochora.datapipeline.api.contracts;

import java.util.List;

/**
 * A POJO representing the dynamic state of the simulation at a single point in time. This is the
 * primary high-volume message that flows through the pipeline.
 */
public class RawTickData {

    private String simulationRunId;
    private long tickNumber;
    private List<RawCellState> cells;
    private List<RawOrganismState> organisms;

    /**
     * Default constructor for deserialization.
     */
    public RawTickData() {
    }

    // Getters and setters

    public String getSimulationRunId() {
        return simulationRunId;
    }

    public void setSimulationRunId(String simulationRunId) {
        this.simulationRunId = simulationRunId;
    }

    public long getTickNumber() {
        return tickNumber;
    }

    public void setTickNumber(long tickNumber) {
        this.tickNumber = tickNumber;
    }

    public List<RawCellState> getCells() {
        return cells;
    }

    public void setCells(List<RawCellState> cells) {
        this.cells = cells;
    }

    public List<RawOrganismState> getOrganisms() {
        return organisms;
    }

    public void setOrganisms(List<RawOrganismState> organisms) {
        this.organisms = organisms;
    }
}
