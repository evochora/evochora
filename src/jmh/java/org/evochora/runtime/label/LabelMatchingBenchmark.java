package org.evochora.runtime.label;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.evochora.runtime.Config;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.OrganismRandom;
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
import org.openjdk.jmh.annotations.Warmup;

/**
 * JMH benchmark for one label lookup of {@link HammingLabelMatchingStrategy}.
 * <p>
 * The index is filled directly, without a simulation: a population of organisms spread over a
 * world of production size, every organism owning the same label values — one label per value, as
 * relatives do, whose label values pass unchanged from parent to child. One value's list is
 * therefore as long as the population. {@link #findTarget()} measures the time of a single lookup
 * in one of the situations of {@link #scenario}; {@link #birthAndDeath()} measures what the index
 * costs on the write path.
 * <p>
 * The tick benchmark measures what a lookup costs a whole tick; this one separates the lookup from
 * everything else, which is what a change to the index or the search needs.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 5, time = 3)
public class LabelMatchingBenchmark {

    private static final int WORLD_WIDTH = 2048;
    private static final int WORLD_HEIGHT = 1152;

    /** Label values every organism owns: the label count of a primordial genome. */
    private static final int VALUES = 74;

    /** Width and height of the block an organism's labels fill, and of the plot the organism stands on. */
    private static final int BLOCK_WIDTH = 16;
    private static final int BLOCK_HEIGHT = 5;

    /** Number of prepared lookups the benchmark cycles through; a power of two. */
    private static final int CALLS = 4096;

    /** The rows that hold every label in the scenario without a reachable target. */
    private static final int BAND_HEIGHT = 600;

    /** The rows the callers stand in there: farther from the band than the foreign reach, either way around. */
    private static final int REMOTE_FROM = 860;
    private static final int REMOTE_TO = 895;

    /** Number of organisms, and with it the number of labels that carry one value. */
    @Param({"200", "2000", "10000"})
    private int labelsPerValue;

    /**
     * The situation a lookup is in. {@code OWN}: an organism looks up a label of its own — the
     * regular case. {@code OWN_FUZZY}: the same with one bit of the searched value flipped, as a
     * mutation leaves it, so that no own label matches exactly. {@code FOREIGN_NEAR}: a caller
     * without labels, anywhere in an evenly populated world, reaches the nearest label of another
     * organism. {@code FOREIGN_MISS}: all labels lie in a band of rows and the callers stand in the
     * same columns beyond the foreign reach, so the search has many labels nearby in one direction
     * and finds none.
     */
    @Param({"OWN", "OWN_FUZZY", "FOREIGN_NEAR", "FOREIGN_MISS"})
    private String scenario;

    private HammingLabelMatchingStrategy strategy;
    private Environment environment;
    private OrganismRandom random;

    private final int[] searchValues = new int[CALLS];
    private final int[] callers = new int[CALLS];
    private final int[][] callerCoords = new int[CALLS][];
    private int next;

    /** The label values every organism owns, and the free plot and owner id of the organism being born. */
    private int[] values;
    private int[] newbornFlatIndexes;
    private int newbornId;

    /**
     * Fills the index, prepares the lookups and checks that each of them ends as its scenario says.
     */
    @Setup(Level.Trial)
    public void fillIndex() {
        EnvironmentProperties props = new EnvironmentProperties(new int[]{WORLD_WIDTH, WORLD_HEIGHT}, true);
        strategy = new HammingLabelMatchingStrategy();
        environment = new Environment(props, strategy);
        random = new OrganismRandom(1);
        random.beginTick(42L);
        Random setupRandom = new Random(42);

        values = distinctValues(setupRandom);
        boolean band = "FOREIGN_MISS".equals(scenario);

        // Origins in flat-index order, so that filling an index that keeps sorted lists mostly appends
        int[][] plots = drawPlots(setupRandom, band ? BAND_HEIGHT : WORLD_HEIGHT);
        int[][] origins = Arrays.copyOf(plots, labelsPerValue);
        newbornId = labelsPerValue + 3;
        newbornFlatIndexes = new int[VALUES];
        for (int k = 0; k < VALUES; k++) {
            newbornFlatIndexes[k] = labelFlatIndex(props, plots[labelsPerValue], k);
        }
        Arrays.sort(origins, (a, b) -> Integer.compare(props.toFlatIndex(a), props.toFlatIndex(b)));
        for (int i = 0; i < labelsPerValue; i++) {
            for (int k = 0; k < VALUES; k++) {
                strategy.addLabel(values[k], labelFlatIndex(props, origins[i], k), i + 1);
            }
        }

        int unintended = 0;
        for (int call = 0; call < CALLS; call++) {
            int k = setupRandom.nextInt(VALUES);
            searchValues[call] = values[k];
            int expected;
            if (scenario.startsWith("OWN")) {
                int organism = setupRandom.nextInt(labelsPerValue);
                if ("OWN_FUZZY".equals(scenario)) {
                    searchValues[call] ^= 1 << setupRandom.nextInt(Config.VALUE_BITS);
                }
                callers[call] = organism + 1;
                callerCoords[call] = origins[organism];
                expected = labelFlatIndex(props, origins[organism], k);
            } else {
                // Two callers that own nothing, one of each parity, so that both tie-break directions occur
                callers[call] = labelsPerValue + 1 + (call & 1);
                int y = band
                        ? REMOTE_FROM + setupRandom.nextInt(REMOTE_TO - REMOTE_FROM)
                        : setupRandom.nextInt(WORLD_HEIGHT);
                callerCoords[call] = new int[]{setupRandom.nextInt(WORLD_WIDTH), y};
                expected = -1;
            }
            if (!endsAsIntended(call, expected)) {
                unintended++;
            }
        }
        // A sparse, evenly populated world has the rare spot without a label within reach
        int allowed = "FOREIGN_NEAR".equals(scenario) ? CALLS / 100 : 0;
        if (unintended > allowed) {
            throw new IllegalStateException("Scenario " + scenario + ": " + unintended + " of " + CALLS
                    + " lookups do not end as the scenario says");
        }
    }

    /**
     * Draws one plot per organism, and a last one that stays free, from a grid of plots over the
     * given rows of the world, each plot once, so that no two organisms' labels share a cell.
     */
    private int[][] drawPlots(Random setupRandom, int rows) {
        int plotsPerRow = WORLD_WIDTH / BLOCK_WIDTH;
        int[] plots = new int[plotsPerRow * (rows / BLOCK_HEIGHT)];
        if (labelsPerValue + 1 > plots.length) {
            throw new IllegalStateException(labelsPerValue + " organisms do not fit into " + plots.length + " plots");
        }
        for (int i = 0; i < plots.length; i++) {
            plots[i] = i;
        }
        int[][] origins = new int[labelsPerValue + 1][];
        for (int i = 0; i <= labelsPerValue; i++) {
            int pick = i + setupRandom.nextInt(plots.length - i);
            int plot = plots[pick];
            plots[pick] = plots[i];
            origins[i] = new int[]{plot % plotsPerRow * BLOCK_WIDTH, plot / plotsPerRow * BLOCK_HEIGHT};
        }
        return origins;
    }

    /**
     * Draws label values that lie farther apart than any lookup tolerates, so that a lookup has
     * exactly one matching value.
     */
    private static int[] distinctValues(Random setupRandom) {
        int[] values = new int[VALUES];
        int count = 0;
        while (count < VALUES) {
            int candidate = setupRandom.nextInt(Config.VALUE_MASK + 1);
            boolean apart = true;
            for (int i = 0; i < count && apart; i++) {
                apart = Integer.bitCount(values[i] ^ candidate) > 2 * HammingLabelMatchingStrategy.DEFAULT_TOLERANCE;
            }
            if (apart) {
                values[count++] = candidate;
            }
        }
        return values;
    }

    private static int labelFlatIndex(EnvironmentProperties props, int[] origin, int k) {
        return props.toFlatIndex(new int[]{origin[0] + k % BLOCK_WIDTH, origin[1] + k / BLOCK_WIDTH});
    }

    /**
     * Whether a prepared lookup ends as its scenario says: an own lookup at the own label, a lookup
     * without a reachable target at nothing, a lookup in the evenly populated world at some target.
     */
    private boolean endsAsIntended(int call, int expected) {
        int target = lookup(call);
        return "FOREIGN_NEAR".equals(scenario) ? target >= 0 : target == expected;
    }

    private int lookup(int call) {
        return strategy.findTarget(searchValues[call], callers[call], callerCoords[call], environment, random);
    }

    /**
     * Measures one label lookup.
     *
     * @return the flat index of the target, or -1 (prevents dead-code elimination)
     */
    @Benchmark
    public int findTarget() {
        int call = next;
        next = (call + 1) & (CALLS - 1);
        return lookup(call);
    }

    /**
     * Measures the write path of one organism's life: its labels enter the index at birth, pass to
     * no owner at its death, and leave the index when the cells are overwritten.
     *
     * @return the number of labels written (prevents dead-code elimination)
     */
    @Benchmark
    public int birthAndDeath() {
        for (int k = 0; k < VALUES; k++) {
            strategy.addLabel(values[k], newbornFlatIndexes[k], newbornId);
        }
        for (int k = 0; k < VALUES; k++) {
            strategy.changeOwner(values[k], newbornFlatIndexes[k], newbornId, 0);
        }
        for (int k = 0; k < VALUES; k++) {
            strategy.removeLabel(values[k], newbornFlatIndexes[k], 0);
        }
        return VALUES;
    }
}
