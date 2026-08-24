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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.ConfigFactory;

/**
 * The policy cache is indexed by opcode id and written from several threads, because instructions
 * execute concurrently in the first wave of a tick.
 * <p>
 * It is safe only as long as the array itself never changes: a slot always receives the same policy
 * instance, but replacing the array is not idempotent. A cache that grew on demand let one thread
 * index the shorter array it had read before another thread enlarged it — an
 * {@code ArrayIndexOutOfBoundsException} in the middle of a tick, which the virtual machine turns
 * into an instruction failure and which therefore looked like a defect in the organism's program.
 * <p>
 * This test reaches across the whole opcode range from many threads at once. It cannot fail on the
 * current implementation; it fails again the moment the cache is made to grow.
 */
@Tag("unit")
@Disabled("""
        Kept for the next change to the policy cache, not for the suite: it needs three thousand \
        attempts and about two seconds to catch the race reliably, and it only catches it at all if \
        the cache is made to grow again. Remove this annotation to run it.""")
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
                pool.shutdownNow();
            }
        }

        assertThat(failures)
                .as("looking up a policy must never fail, whichever thread reaches the cache first")
                .isEmpty();
    }

    /**
     * Ten registered opcodes spread evenly from the lowest to the highest, and probed from the
     * largest downwards.
     * <p>
     * The spread is what makes this test able to fail. A cache that grows on demand only loses work
     * when threads enlarge it to different sizes at the same time: one asks for a high index and
     * enlarges the array, another asks for a lower one, computes a smaller size from the length it
     * read earlier, and replaces the larger array with its own. Ten opcodes clustered at the top
     * would take the cache to its full size on the first lookup and never expose that.
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
