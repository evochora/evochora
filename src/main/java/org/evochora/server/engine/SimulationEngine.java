package org.evochora.server.engine;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.worldgen.EnergyStrategyFactory;
import org.evochora.server.IControllable;
import org.evochora.server.config.SimulationConfiguration;
import org.evochora.server.contracts.raw.RawTickState;
import org.evochora.server.queue.ITickMessageQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the simulation lifecycle, including running, pausing, and stopping.
 */
public class SimulationEngine implements IControllable, Runnable {
    private static final Logger logger = LoggerFactory.getLogger(SimulationEngine.class);
    private final Simulation simulation;
    private final ITickMessageQueue messageQueue;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);

    public SimulationEngine(
            final SimulationConfiguration configuration,
            final Collection<ProgramArtifact> artifacts,
            final ITickMessageQueue messageQueue) {

        // CORRECTED: Create the Environment and RandomProvider from the configuration first.
        final var randomProvider = new SeededRandomProvider(configuration.seed);
        final var environment = new Environment(
                configuration.environment.shape,
                configuration.environment.toroidal
        );

        // CORRECTED: Pass the newly created objects to the Simulation constructor.
        this.simulation = new Simulation(environment, randomProvider, artifacts);

        // Seed energy strategies
        final var energyFactory = new EnergyStrategyFactory(randomProvider);
        configuration.energyStrategies.forEach(strategyConfig ->
                this.simulation.addEnergyStrategy(energyFactory.create(strategyConfig))
        );

        this.messageQueue = messageQueue;
    }

    @Override
    public void run() {
        running.set(true);
        logger.info("Simulation engine started.");
        while (running.get()) {
            if (!paused.get()) {
                tick();
            } else {
                try {
                    // Prevent busy-waiting while paused
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running.set(false);
                }
            }
        }
        logger.info("Simulation engine stopped.");
    }

    private void tick() {
        try {
            simulation.tick();
            final RawTickState rawTickState = WorldStateAdapter.fromSimulation(simulation);
            messageQueue.put(rawTickState);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running.set(false);
            logger.warn("Tick interrupted, stopping simulation engine.");
        } catch (Exception e) {
            logger.error("An error occurred during simulation tick.", e);
            running.set(false);
        }
    }

    @Override
    public void pause() {
        paused.set(true);
        logger.info("Simulation engine paused.");
    }

    @Override
    public void resume() {
        paused.set(false);
        logger.info("Simulation engine resumed.");
    }

    @Override
    public boolean isPaused() {
        return paused.get();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void exit() {
        running.set(false);
        logger.info("Simulation engine stopping.");
    }

    public Simulation getSimulation() {
        return simulation;
    }
}
