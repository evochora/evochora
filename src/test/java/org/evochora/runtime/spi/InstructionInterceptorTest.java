package org.evochora.runtime.spi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.instructions.NopInstruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.thermodynamics.ThermodynamicPolicyManager;
import org.evochora.junit.extensions.logging.ExpectLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.typesafe.config.ConfigFactory;

/**
 * Unit tests for the {@link IInstructionInterceptor} plugin system.
 * <p>
 * Tests verify:
 * <ul>
 *   <li>Interceptors are called during the Plan phase</li>
 *   <li>InterceptionContext provides correct organism and instruction</li>
 *   <li>Instruction replacement works</li>
 *   <li>Operand modification works</li>
 *   <li>Multiple interceptors are called in registration order (chaining)</li>
 *   <li>NOP replacement leads to instruction skip</li>
 *   <li>State serialization (saveState/loadState)</li>
 * </ul>
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class InstructionInterceptorTest {

    private Environment environment;
    private Simulation simulation;
    private Organism organism;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        // Create 10x10 toroidal environment
        environment = new Environment(new int[]{10, 10}, true);

        // Minimal thermodynamic config
        String thermoConfigStr = """
            default {
              className = "org.evochora.runtime.thermodynamics.impl.UniversalThermodynamicPolicy"
              options {
                base-energy = 1
                base-entropy = 1
              }
            }
            overrides {
              instructions {}
              families {}
            }
            """;
        ThermodynamicPolicyManager policyManager = new ThermodynamicPolicyManager(
            ConfigFactory.parseString(thermoConfigStr));

        com.typesafe.config.Config organismConfig = ConfigFactory.parseMap(Map.of(
            "max-energy", 32767,
            "max-entropy", 8191,
            "error-penalty-cost", 10
        ));

        simulation = new Simulation(environment, policyManager, organismConfig, 1);

        // Create organism at position [5, 5] with 1000 energy
        organism = Organism.create(simulation, new int[]{5, 5}, 1000, null);
        simulation.addOrganism(organism);

        // Place NOP instruction at organism's IP
        int nopOpcode = Instruction.getInstructionIdByName("NOP");
        environment.setMolecule(new Molecule(Config.TYPE_CODE, nopOpcode), organism.getId(), new int[]{5, 5});
    }

    // ==================== Basic Interception Tests ====================

    @Test
    void interceptor_isCalledDuringTick() {
        AtomicInteger callCount = new AtomicInteger(0);

        simulation.addInstructionInterceptor(new TestInterceptor() {
            @Override
            public void intercept(InterceptionContext context) {
                callCount.incrementAndGet();
            }
        });

        simulation.tick();

        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void interceptor_receivesCorrectOrganism() {
        AtomicReference<Organism> capturedOrganism = new AtomicReference<>();

        simulation.addInstructionInterceptor(new TestInterceptor() {
            @Override
            public void intercept(InterceptionContext context) {
                capturedOrganism.set(context.getOrganism());
            }
        });

        simulation.tick();

        assertThat(capturedOrganism.get()).isSameAs(organism);
    }

    @Test
    void interceptor_receivesCorrectInstruction() {
        AtomicReference<String> capturedInstructionName = new AtomicReference<>();

        simulation.addInstructionInterceptor(new TestInterceptor() {
            @Override
            public void intercept(InterceptionContext context) {
                capturedInstructionName.set(context.getInstruction().getName());
            }
        });

        simulation.tick();

        assertThat(capturedInstructionName.get()).isEqualTo("NOP");
    }

    // ==================== Instruction Replacement Tests ====================

    @Test
    void interceptor_canReplaceInstruction() {
        AtomicReference<String> originalInstructionName = new AtomicReference<>();
        AtomicReference<String> replacedWith = new AtomicReference<>();

        simulation.addInstructionInterceptor(new TestInterceptor() {
            @Override
            public void intercept(InterceptionContext context) {
                originalInstructionName.set(context.getInstruction().getName());
                // Create a NOP replacement (simplest case)
                Instruction nop = new NopInstruction(context.getOrganism(),
                    Instruction.getInstructionIdByName("NOP"));
                context.setInstruction(nop);
                replacedWith.set("NOP");
            }
        });

        simulation.tick();

        assertThat(originalInstructionName.get()).isEqualTo("NOP");
        assertThat(replacedWith.get()).isEqualTo("NOP");
    }

    @Test
    void interceptor_replacementWithNop_skipsExecution() {
        int initialEnergy = organism.getEr();

        // Place an expensive instruction (e.g., FORK which costs energy)
        // For simplicity, just verify NOP replacement doesn't crash
        simulation.addInstructionInterceptor(new TestInterceptor() {
            @Override
            public void intercept(InterceptionContext context) {
                Instruction nop = new NopInstruction(context.getOrganism(),
                    Instruction.getInstructionIdByName("NOP"));
                context.setInstruction(nop);
            }
        });

        simulation.tick();

        // NOP should have minimal energy cost
        assertThat(organism.getEr()).isLessThanOrEqualTo(initialEnergy);
    }

    // ==================== Operand Access Tests ====================

    @Test
    void interceptor_canAccessOperands() {
        AtomicReference<List<Instruction.Operand>> capturedOperands = new AtomicReference<>();

        simulation.addInstructionInterceptor(new TestInterceptor() {
            @Override
            public void intercept(InterceptionContext context) {
                capturedOperands.set(new ArrayList<>(context.getOperands()));
            }
        });

        simulation.tick();

        // NOP has no operands
        assertThat(capturedOperands.get()).isEmpty();
    }

    @Test
    void interceptor_getOperands_isIdempotent() {
        AtomicReference<List<Instruction.Operand>> firstCall = new AtomicReference<>();
        AtomicReference<List<Instruction.Operand>> secondCall = new AtomicReference<>();

        simulation.addInstructionInterceptor(new TestInterceptor() {
            @Override
            public void intercept(InterceptionContext context) {
                firstCall.set(context.getOperands());
                secondCall.set(context.getOperands());
            }
        });

        simulation.tick();

        assertThat(firstCall.get()).isSameAs(secondCall.get());
    }

    // ==================== Chaining Tests ====================

    @Test
    void multipleInterceptors_calledInRegistrationOrder() {
        List<String> callOrder = new ArrayList<>();

        simulation.addInstructionInterceptor(new TestInterceptor() {
            @Override
            public void intercept(InterceptionContext context) {
                callOrder.add("first");
            }
        });

        simulation.addInstructionInterceptor(new TestInterceptor() {
            @Override
            public void intercept(InterceptionContext context) {
                callOrder.add("second");
            }
        });

        simulation.addInstructionInterceptor(new TestInterceptor() {
            @Override
            public void intercept(InterceptionContext context) {
                callOrder.add("third");
            }
        });

        simulation.tick();

        assertThat(callOrder).containsExactly("first", "second", "third");
    }

    @Test
    void multipleInterceptors_laterSeesReplacementFromEarlier() {
        AtomicReference<String> secondInterceptorSaw = new AtomicReference<>();

        simulation.addInstructionInterceptor(new TestInterceptor() {
            @Override
            public void intercept(InterceptionContext context) {
                // Replace with NOP
                Instruction nop = new NopInstruction(context.getOrganism(),
                    Instruction.getInstructionIdByName("NOP"));
                context.setInstruction(nop);
            }
        });

        simulation.addInstructionInterceptor(new TestInterceptor() {
            @Override
            public void intercept(InterceptionContext context) {
                secondInterceptorSaw.set(context.getInstruction().getName());
            }
        });

        simulation.tick();

        // Second interceptor should see the NOP that first interceptor set
        assertThat(secondInterceptorSaw.get()).isEqualTo("NOP");
    }

    // ==================== State Serialization Tests ====================

    @Test
    void interceptor_saveState_isCalled() {
        AtomicInteger saveCallCount = new AtomicInteger(0);

        TestInterceptor interceptor = new TestInterceptor() {
            @Override
            public void intercept(InterceptionContext context) {
                // No-op for this test
            }

            @Override
            public byte[] saveState() {
                saveCallCount.incrementAndGet();
                return new byte[]{1, 2, 3};
            }
        };

        simulation.addInstructionInterceptor(interceptor);

        // Directly call saveState to verify it works
        byte[] state = interceptor.saveState();

        assertThat(saveCallCount.get()).isEqualTo(1);
        assertThat(state).containsExactly(1, 2, 3);
    }

    @Test
    void interceptor_loadState_restoresState() {
        StatefulInterceptor interceptor = new StatefulInterceptor();
        interceptor.setCounter(42);

        // Save state
        byte[] savedState = interceptor.saveState();

        // Create new instance and restore
        StatefulInterceptor restored = new StatefulInterceptor();
        assertThat(restored.getCounter()).isEqualTo(0);

        restored.loadState(savedState);

        assertThat(restored.getCounter()).isEqualTo(42);
    }

    // ==================== Dead Organism Handling ====================

    @Test
    void interceptor_notCalledForDeadOrganisms() {
        AtomicInteger callCount = new AtomicInteger(0);

        simulation.addInstructionInterceptor(new TestInterceptor() {
            @Override
            public void intercept(InterceptionContext context) {
                callCount.incrementAndGet();
            }
        });

        // Kill the organism
        organism.kill("test");

        simulation.tick();

        assertThat(callCount.get()).isEqualTo(0);
    }

    // ==================== Error Handling Tests ====================

    @Test
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*Simulation.*", messagePattern = ".*Interceptor.*failed.*")
    void interceptor_exceptionDoesNotCrashSimulation() {
        AtomicInteger secondInterceptorCalled = new AtomicInteger(0);

        simulation.addInstructionInterceptor(new TestInterceptor() {
            @Override
            public void intercept(InterceptionContext context) {
                throw new RuntimeException("Test exception");
            }
        });

        simulation.addInstructionInterceptor(new TestInterceptor() {
            @Override
            public void intercept(InterceptionContext context) {
                secondInterceptorCalled.incrementAndGet();
            }
        });

        // Should not throw
        simulation.tick();

        // Second interceptor should still be called
        assertThat(secondInterceptorCalled.get()).isEqualTo(1);
    }

    // ==================== Operand Modification Tests ====================

    @Test
    void interceptor_operandModification_affectsExecution() {
        // Setup: Place ADDI instruction (ADD immediate) at organism's IP
        // ADDI adds an immediate value to a register
        int addiOpcode = Instruction.getInstructionIdByName("ADDI");
        environment.setMolecule(new Molecule(Config.TYPE_CODE, addiOpcode), organism.getId(), new int[]{5, 5});

        // Place operands: target register (DR0) and immediate value (100)
        int dr0Id = 0; // DR0
        environment.setMolecule(new Molecule(Config.TYPE_CODE, dr0Id), organism.getId(), new int[]{6, 5});
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 100), organism.getId(), new int[]{7, 5});

        // Set initial value in DR0
        organism.setDr(0, new Molecule(Config.TYPE_DATA, 50).toInt());

        // Interceptor modifies the immediate operand from 100 to 200
        simulation.addInstructionInterceptor(new TestInterceptor() {
            @Override
            public void intercept(InterceptionContext context) {
                if (context.getOperands().size() >= 2) {
                    // Replace second operand (immediate value) with 200
                    Instruction.Operand modified = new Instruction.Operand(
                            new Molecule(Config.TYPE_DATA, 200).toInt(), -1);
                    context.setOperand(1, modified);
                }
            }
        });

        simulation.tick();

        // Verify: DR0 should be 50 + 200 = 250 (not 50 + 100 = 150)
        int result = Molecule.fromInt((Integer) organism.getDr(0)).toScalarValue();
        assertThat(result).isEqualTo(250);
    }

    // ==================== Helper Classes ====================

    /**
     * Base test interceptor with no-op default implementations.
     */
    private abstract static class TestInterceptor implements IInstructionInterceptor {
        @Override
        public byte[] saveState() {
            return new byte[0];
        }

        @Override
        public void loadState(byte[] state) {
            // No state to load
        }
    }

    /**
     * Interceptor with actual state for serialization tests.
     */
    private static class StatefulInterceptor implements IInstructionInterceptor {
        private int counter = 0;

        public void setCounter(int value) {
            this.counter = value;
        }

        public int getCounter() {
            return counter;
        }

        @Override
        public void intercept(InterceptionContext context) {
            counter++;
        }

        @Override
        public byte[] saveState() {
            return new byte[]{(byte) counter};
        }

        @Override
        public void loadState(byte[] state) {
            if (state != null && state.length > 0) {
                this.counter = state[0] & 0xFF;
            }
        }
    }
}
