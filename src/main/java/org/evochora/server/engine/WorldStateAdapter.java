package org.evochora.server.engine;

import org.evochora.runtime.Simulation;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Organism;
import org.evochora.server.contracts.raw.RawCellState;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.server.contracts.raw.RawTickState;

import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * Adapts the internal state of the Simulation into raw, persistable DTOs.
 */
public class WorldStateAdapter {

    public static RawTickState fromSimulation(final Simulation simulation) {
        final var organisms = simulation.getOrganisms();
        final Environment environment = simulation.getEnvironment();
        final var tick = simulation.getTickNumber();

        final var organismStates = organisms.stream()
                .map(WorldStateAdapter::toRawOrganismState)
                .collect(Collectors.toCollection(ArrayList::new));

        final var cellStates = new ArrayList<RawCellState>();
        // CORRECTED: Iterate over the entrySet() of the Environment map, which is the standard and correct way.
        for (final var entry : environment.entrySet()) {
            final var molecule = entry.getValue();
            if (!molecule.isFree()) {
                cellStates.add(new RawCellState(
                        entry.getKey(),
                        molecule.getRawValue(),
                        molecule.getOwnerId()
                ));
            }
        }

        return new RawTickState(tick, organismStates, cellStates);
    }

    private static RawOrganismState toRawOrganismState(final Organism organism) {
        return new RawOrganismState(
                organism.getId(),
                organism.getProgramId(),
                organism.getParentId(),
                organism.getBirthTick(),
                organism.getEnergy(),
                organism.getIp(),
                organism.getDv(),
                organism.getDps(),
                organism.getActiveDpIndex(),
                organism.getDrs(),
                organism.getPrs(),
                organism.getFprs(),
                organism.getLrs(),
                organism.getDataStack(),
                organism.getLocationStack(),
                organism.getCallStack()
        );
    }
}
