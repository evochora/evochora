package org.evochora.runtime.thermodynamics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.instructions.NopInstruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.typesafe.config.ConfigFactory;

/**
 * Reaches the policy cache from many threads at once, across the whole opcode range.
 * <p>
 * The cache is indexed by opcode id and written from several threads, because instructions execute
 * concurrently in the first wave of a tick. That is safe as long as the array itself never changes:
 * a slot always receives the same policy instance, so filling it twice is harmless, but replacing
 * the array would not be. A cache sized on demand would let one thread index the shorter array it
 * had read before another thread enlarged it, which surfaces as an
 * {@code ArrayIndexOutOfBoundsException} in the middle of a tick — the virtual machine turns that
 * into an instruction failure, so it reads as a defect in the organism's program.
 * <p>
 * The test passes on a fixed-size cache and would fail on a growing one. It carries no tag because
 * it fits neither the unit budget (under 0.2s) nor the integration one: three thousand attempts and
 * roughly two seconds are what it takes to hit the interleaving reliably. Remove {@code @Disabled}
 * to run it while working on this cache.
 */
@Disabled("Too slow for the suite; see the class comment for what it covers and when to run it.")
class ThermodynamicPolicyManagerConcurrencyTest {

    private static final String THERMO_CONFIG = """
            default {
              className = "org.evochora.runtime.thermodynamics.impl.UniversalThermodynamicPolicy"
              options { base-energy = 1, base-entropy = 1 }
            }
            overrides { instructions {}, families {} }
            """;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void concurrentLookupsAcrossTheOpcodeRangeNeverFail() throws Exception {
        List<Integer> opcodeIds = spreadAcrossTheOpcodeRange();
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        // Each attempt starts from a fresh manager, because a cache is only ever populated during the
        // warm-up of a run — which is exactly what a resume creates in the middle of one.
        for (int attempt = 0; attempt < 3000 && failures.isEmpty(); attempt++) {
            ThermodynamicPolicyManager manager =
                    new ThermodynamicPolicyManager(ConfigFactory.parseString(THERMO_CONFIG));

            CountDownLatch startTogether = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(opcodeIds.size());
            ExecutorService pool = Executors.newFixedThreadPool(opcodeIds.size());
            try {
                for (int opcodeId : opcodeIds) {
                    pool.execute(() -> {
                        try {
                            startTogether.await();
                            assertThat(manager.getPolicy(new NopInstruction(null, opcodeId)))
                                    .as("policy for opcode %d", opcodeId)
                                    .isNotNull();
                        } catch (Throwable t) {
                            failures.add(t);
                        } finally {
                            finished.countDown();
                        }
                    });
                }
                startTogether.countDown();
                assertThat(finished.await(10, TimeUnit.SECONDS)).as("all lookups finished").isTrue();
            } finally {
                shutDownAndWait(pool);
            }
        }

        assertThat(failures)
                .as("looking up a policy must never fail, whichever thread reaches the cache first")
                .isEmpty();
    }

    /**
     * Ends one attempt's pool and waits for its threads to be gone.
     * <p>
     * {@code shutdownNow} only interrupts the running tasks and returns, so without the wait an
     * attempt whose lookups timed out would leave its threads behind while the next attempt starts
     * its own. Threads from an earlier attempt still reaching the cache would blur what this test
     * observes, and they would do so exactly when it has something to report.
     *
     * @param pool the pool of the attempt that just ended
     */
    private static void shutDownAndWait(ExecutorService pool) {
        pool.shutdownNow();
        try {
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS))
                    .as("the threads of one attempt must be gone before the next one starts")
                    .isTrue();
        } catch (InterruptedException e) {
            // Passing the interruption on: swallowing it here would leave the test running while
            // whoever interrupted it waits for it to stop.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the lookup threads", e);
        }
    }

    /**
     * Ten registered opcodes spread evenly from the lowest to the highest, and probed from the
     * largest downwards.
     * <p>
     * The spread is what gives this test its teeth. A cache sized on demand would only lose work when
     * threads enlarge it to different sizes at once: one asks for a high index and enlarges the
     * array, another asks for a lower one, computes a smaller size from the length it read earlier,
     * and replaces the larger array with its own. Ten opcodes clustered at the top would reach the
     * full size on the first lookup, and that interleaving could never arise.
     * <p>
     * Taken from the registry rather than written down, so the test keeps covering the range when
     * instructions are added — and cannot ask for an opcode that does not exist, which the virtual
     * machine would never let reach the policy manager either.
     */
    private static List<Integer> spreadAcrossTheOpcodeRange() {
        List<Integer> registered = Instruction.getInstructionSetInfo().stream()
                .map(Instruction.InstructionInfo::opcodeId)
                .sorted()
                .toList();

        int probes = 10;
        List<Integer> ids = new ArrayList<>(probes);
        for (int i = probes - 1; i >= 0; i--) {
            ids.add(registered.get(i * (registered.size() - 1) / (probes - 1)));
        }
        assertThat(ids).as("registered instructions to probe with").hasSize(probes);
        return ids;
    }
}
