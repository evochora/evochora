package org.evochora.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.instructions.EnvironmentInteractionInstruction;
import org.evochora.runtime.isa.instructions.NopInstruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.spi.DeathContext;
import org.evochora.runtime.spi.IBirthHandler;
import org.evochora.runtime.spi.IDeathHandler;
import org.evochora.runtime.spi.IInstructionInterceptor;
import org.evochora.runtime.spi.IRandomProvider;
import org.evochora.runtime.spi.ITickPlugin;
import org.evochora.runtime.spi.InterceptionContext;
import org.evochora.runtime.thermodynamics.ThermodynamicPolicyManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.ConfigFactory;

/**
 * A defect that throws while a tick works on an organism ends the tick with a
 * {@link SimulationFault} that names the tick, the organism and the instruction, carries the
 * original exception as its cause, and books nothing against the organism.
 */
@Tag("unit")
class SimulationFaultTest {

    private static final String DEFECT = "test-induced defect";

    private Simulation simulation;
    private Organism organism;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @AfterEach
    void tearDown() {
        if (simulation != null) {
            simulation.shutdown();
        }
    }

    private void createSimulation(boolean toroidal, int parallelism, int[] organismPosition) {
        Environment environment = new Environment(new int[]{32, 32}, toroidal);
        ThermodynamicPolicyManager policyManager = new ThermodynamicPolicyManager(
            ConfigFactory.parseString("""
                default {
                  className = "org.evochora.runtime.thermodynamics.impl.UniversalThermodynamicPolicy"
                  options { base-energy = 1, base-entropy = 1 }
                }
                overrides { instructions {}, families {} }
                """));
        com.typesafe.config.Config organismConfig = ConfigFactory.parseMap(Map.of(
            "max-energy", 32767,
            "max-entropy", 8191,
            "error-penalty-cost", 10
        ));
        simulation = new Simulation(environment, policyManager, organismConfig, parallelism);
        simulation.setRandomProvider(new SeededRandomProvider(42L));

        organism = Organism.create(simulation, organismPosition, 1000);
        simulation.addOrganism(organism);
        if (environment.exists(organismPosition)) {
            int nopOpcode = Instruction.getInstructionIdByName("NOP");
            environment.setMolecule(new Molecule(Config.TYPE_CODE, nopOpcode), organism.getId(), organismPosition);
        }
    }

    /** Replaces every planned instruction by one whose execution throws. */
    private void installThrowingInstruction() {
        int nopOpcode = Instruction.getInstructionIdByName("NOP");
        simulation.addInstructionInterceptor(new IInstructionInterceptor() {
            @Override
            public void intercept(InterceptionContext context) {
                context.setInstruction(new NopInstruction(context.getOrganism(), nopOpcode) {
                    @Override
                    public void execute(ExecutionContext executionContext) {
                        throw new IllegalStateException(DEFECT);
                    }
                });
            }

            @Override
            public byte[] saveState() {
                return new byte[0];
            }

            @Override
            public void loadState(byte[] state) {
                // Stateless
            }
        });
    }

    @Test
    void faultInExecutionNamesTickOrganismInstructionAndPosition() {
        createSimulation(true, 1, new int[]{5, 5});
        installThrowingInstruction();

        assertThatThrownBy(simulation::tick)
            .isInstanceOf(SimulationFault.class)
            .hasMessageContaining("Organism " + organism.getId())
            .hasMessageContaining("at tick 0")
            .hasMessageContaining("instruction NOP")
            .hasMessageContaining("at [5, 5]")
            .cause()
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(DEFECT);
    }

    @Test
    void faultBooksNothingAgainstTheOrganism() {
        createSimulation(true, 1, new int[]{5, 5});
        installThrowingInstruction();

        assertThatThrownBy(simulation::tick).isInstanceOf(SimulationFault.class);

        assertThat(organism.isInstructionFailed()).isFalse();
        assertThat(organism.getLastInstructionExecution()).isNull();
        assertThat(organism.isDead()).isFalse();
    }

    @Test
    void faultInPlanningNamesTheOrganismWithoutAnInstruction() {
        // A bounded world does not wrap, so an organism placed outside it has no cell to fetch
        // from: the fetch throws before any instruction exists.
        createSimulation(false, 1, new int[]{-1, 5});

        assertThatThrownBy(simulation::tick)
            .isInstanceOf(SimulationFault.class)
            .hasMessageContaining("Organism " + organism.getId())
            .hasMessageContaining("at tick 0")
            .hasMessageNotContaining("instruction")
            .cause()
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void faultInConflictResolutionNamesTheInstruction() {
        createSimulation(true, 1, new int[]{5, 5});
        // An environment-modifying instruction is resolved for conflicts before it runs; this one
        // throws where its target cell is asked for.
        int pokeOpcode = Instruction.getInstructionIdByName("POKE");
        simulation.addInstructionInterceptor(new IInstructionInterceptor() {
            @Override
            public void intercept(InterceptionContext context) {
                context.setInstruction(new EnvironmentInteractionInstruction(context.getOrganism(), pokeOpcode) {
                    @Override
                    public java.util.List<int[]> getTargetCoordinates() {
                        throw new IllegalStateException(DEFECT);
                    }
                });
            }

            @Override
            public byte[] saveState() {
                return new byte[0];
            }

            @Override
            public void loadState(byte[] state) {
                // Stateless
            }
        });

        assertThatThrownBy(simulation::tick)
            .isInstanceOf(SimulationFault.class)
            .hasMessageContaining("Organism " + organism.getId())
            .hasMessageContaining("instruction POKE")
            .cause()
            .hasMessage(DEFECT);
    }

    @Test
    void faultOnAWorkerThreadReachesTheCallerUnchanged() {
        createSimulation(true, 2, new int[]{5, 5});
        Organism second = Organism.create(simulation, new int[]{9, 9}, 1000);
        simulation.addOrganism(second);
        installThrowingInstruction();

        assertThatThrownBy(simulation::tick)
            .isInstanceOf(SimulationFault.class)
            .cause()
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(DEFECT);
    }
    /** A plugin of every kind the tick loop runs, each one throwing when it is called. */
    private static final class ThrowingPlugin implements ITickPlugin, IBirthHandler, IDeathHandler,
            IInstructionInterceptor {
        @Override
        public void execute(Simulation simulation) {
            throw new IllegalStateException(DEFECT);
        }

        @Override
        public void onBirth(Organism child, Environment environment) {
            throw new IllegalStateException(DEFECT);
        }

        @Override
        public void onDeath(DeathContext context) {
            throw new IllegalStateException(DEFECT);
        }

        @Override
        public void intercept(InterceptionContext context) {
            throw new IllegalStateException(DEFECT);
        }

        @Override
        public byte[] saveState() {
            return new byte[0];
        }

        @Override
        public void loadState(byte[] state) {
            // Stateless
        }
    }

    @Test
    void faultInATickPluginNamesThePluginAndTheTick() {
        createSimulation(true, 1, new int[]{5, 5});
        simulation.addTickPlugin(new ThrowingPlugin());

        assertThatThrownBy(simulation::tick)
            .isInstanceOf(SimulationFault.class)
            .hasMessageContaining(ThrowingPlugin.class.getName())
            .hasMessageContaining("at tick 0")
            .hasMessageNotContaining("for organism")
            .cause()
            .hasMessage(DEFECT);
    }

    @Test
    void faultInAnInterceptorNamesTheOrganism() {
        createSimulation(true, 1, new int[]{5, 5});
        simulation.addInstructionInterceptor(new ThrowingPlugin());

        assertThatThrownBy(simulation::tick)
            .isInstanceOf(SimulationFault.class)
            .hasMessageContaining(ThrowingPlugin.class.getName())
            .hasMessageContaining("for organism " + organism.getId())
            .cause()
            .hasMessage(DEFECT);
    }

    @Test
    void faultInABirthHandlerNamesTheNewborn() {
        createSimulation(true, 1, new int[]{5, 5});
        simulation.addBirthHandler(new ThrowingPlugin());
        Organism newborn = Organism.create(simulation, new int[]{9, 9}, 100);
        simulation.addNewOrganism(newborn);

        assertThatThrownBy(simulation::tick)
            .isInstanceOf(SimulationFault.class)
            .hasMessageContaining(ThrowingPlugin.class.getName())
            .hasMessageContaining("for organism " + newborn.getId())
            .cause()
            .hasMessage(DEFECT);
    }

    @Test
    void faultAfterABirthNamesTheNewborn() {
        createSimulation(true, 1, new int[]{5, 5});
        Organism newborn = Organism.create(simulation, new int[]{9, 9}, 100);
        simulation.addNewOrganism(newborn);
        // The label rewrite of a newborn that owns a cell draws from the root random provider; a
        // provider that throws there is the defect.
        simulation.getEnvironment().setMolecule(new Molecule(Config.TYPE_DATA, 1), newborn.getId(), new int[]{9, 9});
        simulation.setRandomProvider(new IRandomProvider() {
            @Override
            public long seed() {
                return 42L;
            }

            @Override
            public int nextInt(int bound) {
                throw new IllegalStateException(DEFECT);
            }

            @Override
            public double nextDouble() {
                throw new IllegalStateException(DEFECT);
            }

            @Override
            public java.util.Random asJavaRandom() {
                throw new IllegalStateException(DEFECT);
            }

            @Override
            public byte[] saveState() {
                return new byte[0];
            }

            @Override
            public void loadState(byte[] state) {
                // Stateless
            }
        });

        assertThatThrownBy(simulation::tick)
            .isInstanceOf(SimulationFault.class)
            .hasMessageContaining("Newborn " + newborn.getId())
            .hasMessageContaining("at tick 0")
            .cause()
            .hasMessage(DEFECT);
    }

    @Test
    void faultInADeathHandlerNamesTheDeadOrganism() {
        createSimulation(true, 1, new int[]{5, 5});
        simulation.addDeathHandler(new ThrowingPlugin());
        // One unit of energy: the base cost of the NOP at the start position kills the organism.
        organism.takeEr(organism.getEr() - 1);

        assertThatThrownBy(simulation::tick)
            .isInstanceOf(SimulationFault.class)
            .hasMessageContaining(ThrowingPlugin.class.getName())
            .hasMessageContaining("for organism " + organism.getId())
            .cause()
            .hasMessage(DEFECT);
    }
}
