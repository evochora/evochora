package org.evochora.runtime.internal.services;

import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Organism;

/**
 * Encapsulates all the information and dependencies required at the runtime of an instruction.
 * This object is created by the VirtualMachine and passed to the executing
 * units to avoid global access.
 */
public class ExecutionContext {

    private final Organism organism;
    private final Environment environment;
    private final boolean isPerformanceMode;

    /**
     * Constructs a new ExecutionContext.
     * @param organism The organism executing the instruction.
     * @param environment The environment in which the instruction is executed.
     * @param isPerformanceMode A flag indicating if the simulation is in performance mode.
     */
    public ExecutionContext(Organism organism, Environment environment, boolean isPerformanceMode) {
        this.organism = organism;
        this.environment = environment;
        this.isPerformanceMode = isPerformanceMode;
    }

    /**
     * Returns the organism associated with this execution context.
     * @return The organism.
     */
    public Organism getOrganism() {
        return organism;
    }

    /**
     * Returns the environment associated with this execution context.
     * @return The environment.
     */
    public Environment getWorld() {
        return environment;
    }

    /**
     * Checks if the simulation is running in performance mode.
     * @return true if in performance mode, false otherwise.
     */
    public boolean isPerformanceMode() {
        return isPerformanceMode;
    }
}