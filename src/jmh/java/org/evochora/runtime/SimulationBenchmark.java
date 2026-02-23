package org.evochora.runtime;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.thermodynamics.ThermodynamicPolicyManager;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * JMH benchmark for {@link Simulation#tick()}.
 * <p>
 * Measures simulation throughput (ticks per second) across different
 * parallelism levels and assembly programs. Each iteration starts with
 * a fresh simulation containing a configurable number of organisms, each
 * running the selected program.
 * <p>
 * Run with: {@code ./gradlew jmh}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(2)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
public class SimulationBenchmark {

    private static final int ENV_SIZE = 1024;
    private static final int MAX_ENERGY = 32767;

    /**
     * Thermodynamic configuration matching production defaults.
     * Inline to isolate benchmarks from evochora.conf changes.
     */
    private static final String THERMODYNAMIC_CONFIG = """
            default {
              className = "org.evochora.runtime.thermodynamics.impl.UniversalThermodynamicPolicy"
              options {
                base-energy = 1
                base-entropy = 1
              }
            }
            overrides {
              instructions {
                "PEEK, PEKI, PEKS, POKE, POKI, POKS, PPKR, PPKI, PPKS" {
                  className = "org.evochora.runtime.thermodynamics.impl.UniversalThermodynamicPolicy"
                  options {
                    base-energy = 0
                    base-entropy = 0
                    read-rules {
                      own:       { _default: { energy = 0, entropy = 0 }, ENERGY: { energy = 0, entropy = 0 }, STRUCTURE: { energy = 0, entropy = 0 }, CODE: { energy = 0, entropy = 0 }, DATA: { energy = 0, entropy = 0 } }
                      foreign:   { _default: { energy = 5, entropy = 5 }, ENERGY: { energy-permille = -1000, entropy = 0 }, STRUCTURE: { energy-permille = 1000, entropy-permille = 1000 }, CODE: { energy = 5, entropy = 5 }, DATA: { energy = 5, entropy = 5 } }
                      unowned:   { _default: { energy = 0, entropy = 0 }, ENERGY: { energy-permille = -1000, entropy = 0 }, STRUCTURE: { energy = 0, entropy = 0 }, CODE: { energy = 0, entropy = 0 }, DATA: { energy = 0, entropy = 0 } }
                    }
                    write-rules {
                      ENERGY    { energy-permille = 1000, entropy-permille = 1000 }
                      STRUCTURE { energy-permille = 1000, entropy-permille = 1000 }
                      CODE      { energy = 6, entropy-permille = -1000 }
                      DATA      { energy = 6, entropy-permille = -1000 }
                    }
                  }
                }
              }
              families {}
            }
            """;

    private static final Map<String, String> PROGRAMS = Map.of(
            "ARITHMETIC", """
                    START:
                      SETI %DR0 DATA:1
                      ADDI %DR0 DATA:1
                      SUBI %DR0 DATA:1
                      MULI %DR0 DATA:2
                      JMPI START
                    """,
            "ENVIRONMENT", """
                    START:
                      SETI %DR0 DATA:5
                      POKI %DR0 0|1
                      PEKI %DR1 0|1
                      JMPI START
                    """
    );

    /** Parallelism level for the simulation's Plan and Execute phases. */
    @Param({"1", "2", "4", "8"})
    private int parallelism;

    /** Assembly program to execute. */
    @Param({"ARITHMETIC", "ENVIRONMENT"})
    private String program;

    /** Number of organisms in the simulation. */
    @Param({"500", "2000"})
    private int organismCount;

    private Map<String, ProgramArtifact> compiledPrograms;
    private EnvironmentProperties envProps;
    private Simulation simulation;

    /**
     * Compiles all assembly programs once per JMH fork.
     */
    @Setup(Level.Trial)
    public void compilePrograms() {
        Instruction.init();
        envProps = new EnvironmentProperties(new int[]{ENV_SIZE, ENV_SIZE}, true);
        Compiler compiler = new Compiler();
        compiledPrograms = new HashMap<>();
        for (Map.Entry<String, String> entry : PROGRAMS.entrySet()) {
            List<String> sourceLines = Arrays.asList(entry.getValue().split("\\r?\\n"));
            try {
                compiledPrograms.put(
                        entry.getKey(),
                        compiler.compile(sourceLines, "bench_" + entry.getKey() + ".s", envProps));
            } catch (CompilationException e) {
                throw new RuntimeException("Failed to compile benchmark program: " + entry.getKey(), e);
            }
        }
    }

    /**
     * Creates a fresh simulation with organisms for each measurement iteration.
     */
    @Setup(Level.Iteration)
    public void setupSimulation() {
        Environment env = new Environment(envProps);

        Config organismConfig = ConfigFactory.parseMap(Map.of(
                "max-energy", MAX_ENERGY,
                "max-entropy", 8191,
                "error-penalty-cost", 10
        ));
        ThermodynamicPolicyManager policyManager =
                new ThermodynamicPolicyManager(ConfigFactory.parseString(THERMODYNAMIC_CONFIG));

        simulation = new Simulation(env, policyManager, organismConfig, parallelism);

        ProgramArtifact artifact = compiledPrograms.get(program);
        placeOrganisms(env, artifact);
    }

    /**
     * Measures the throughput of a single simulation tick.
     *
     * @return the current tick count (prevents dead-code elimination)
     */
    @Benchmark
    public long tick() {
        simulation.tick();
        return simulation.getCurrentTick();
    }

    /**
     * Shuts down the simulation's ForkJoinPool after each iteration.
     */
    @TearDown(Level.Iteration)
    public void teardown() {
        simulation.shutdown();
    }

    /**
     * Places multiple copies of a compiled program into the environment and
     * creates one organism per copy.
     */
    private void placeOrganisms(Environment env, ProgramArtifact artifact) {
        Map<int[], Integer> layout = artifact.machineCodeLayout();

        int maxExtent = 0;
        for (int[] coord : layout.keySet()) {
            maxExtent = Math.max(maxExtent, coord[0]);
        }
        int spacing = maxExtent + 5;
        int organismsPerRow = ENV_SIZE / spacing;

        for (int i = 0; i < organismCount; i++) {
            int col = i % organismsPerRow;
            int row = i / organismsPerRow;
            int offsetX = col * spacing;
            int offsetY = row * spacing;

            for (Map.Entry<int[], Integer> entry : layout.entrySet()) {
                int[] coord = entry.getKey();
                int[] placed = new int[]{
                        (coord[0] + offsetX) % ENV_SIZE,
                        (coord.length > 1 ? coord[1] + offsetY : offsetY) % ENV_SIZE
                };
                env.setMolecule(Molecule.fromInt(entry.getValue()), placed);
            }

            int[] startIp = new int[]{offsetX, offsetY};
            Organism org = Organism.create(simulation, startIp, MAX_ENERGY,
                    LoggerFactory.getLogger(SimulationBenchmark.class));
            simulation.addOrganism(org);
        }
    }
}
