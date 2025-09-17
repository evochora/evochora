package org.evochora.datapipeline.api.contracts;

import java.util.List;

/**
 * A message containing the static, immutable context for a simulation run. This message is
 * typically sent once at the beginning of a stream.
 */
public class SimulationContext {

    private String simulationRunId;
    private EnvironmentProperties environment;
    private List<ProgramArtifact> artifacts;

    /**
     * Default constructor for deserialization.
     */
    public SimulationContext() {
    }

    // Getters and setters

    public String getSimulationRunId() {
        return simulationRunId;
    }

    public void setSimulationRunId(String simulationRunId) {
        this.simulationRunId = simulationRunId;
    }

    public EnvironmentProperties getEnvironment() {
        return environment;
    }

    public void setEnvironment(EnvironmentProperties environment) {
        this.environment = environment;
    }

    public List<ProgramArtifact> getArtifacts() {
        return artifacts;
    }

    public void setArtifacts(List<ProgramArtifact> artifacts) {
        this.artifacts = artifacts;
    }
}
