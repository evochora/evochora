package org.evochora.datapipeline.services.analytics.plugins;

import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.analytics.IAnalyticsContext;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.utils.MetadataConfigHelper;

/**
 * Says what a dead organism died of.
 * <p>
 * <strong>The causes:</strong>
 * <ul>
 *   <li>{@code energy} - the organism's energy was used up</li>
 *   <li>{@code entropy} - the organism's entropy exceeded the maximum the run allows</li>
 * </ul>
 * <p>
 * <strong>How the cause is read.</strong> A recording does not state why an organism died. The
 * runtime kills an organism when its energy has dropped to zero or below, or when its entropy
 * exceeds the configured maximum, and it checks in that order. Killing changes neither value, so
 * the cause is read back from the dead organism's state in the same order.
 * <p>
 * <strong>A death that fits neither.</strong> The runtime kills for no other reason, so a dead
 * organism whose state fits neither rule means that the runtime has gained a cause this class does
 * not know. That is refused rather than counted as a third cause: the tables would otherwise hold
 * a cause nobody defined.
 * <p>
 * This is the one place a death is classified: the metric that counts the causes per recording and
 * the one that names the cause of a single death read the same rule, so a cause means the same
 * thing wherever it is written.
 */
final class DeathCause {

    /** The causes, in the order they are numbered in, which is the order the runtime checks. */
    static final List<String> CAUSES = List.of("energy", "entropy");

    /** The number the cause {@code energy} is written as. */
    static final int ENERGY = CAUSES.indexOf("energy");

    /** The number the cause {@code entropy} is written as. */
    static final int ENTROPY = CAUSES.indexOf("entropy");

    /**
     * What each cause says about a death, as a clause that completes "a death where ...". The
     * tables that count the causes or name the cause of a single death describe themselves with
     * these, so that a cause reads the same in every table.
     */
    private static final Map<String, String> MEANINGS = Map.of(
        "energy", "the organism's energy was used up",
        "entropy", "the organism's entropy exceeded the maximum");

    /**
     * Entropy above which the runtime kills an organism, or {@code null} where no run
     * configuration says so.
     */
    private final Integer maxEntropy;

    private DeathCause(Integer maxEntropy) {
        this.maxEntropy = maxEntropy;
    }

    /**
     * Creates the rule for the run a plugin was initialized with.
     * <p>
     * A context without a run configuration - a caller that does not place rows on a run, such as
     * a unit test - yields a rule that knows no entropy limit: it still names a death by energy,
     * and refuses every other one.
     *
     * @param context the context the plugin was initialized with, or {@code null}
     * @return the rule, with the run's entropy limit where the context carries one
     */
    static DeathCause forRun(IAnalyticsContext context) {
        if (context == null || context.getMetadata() == null
                || context.getMetadata().getResolvedConfigJson().isEmpty()) {
            return new DeathCause(null);
        }
        return new DeathCause(MetadataConfigHelper.getResolvedConfig(context.getMetadata())
            .getInt("runtime.organism.max-entropy"));
    }

    /**
     * Says what a cause means.
     *
     * @param cause one of {@link #CAUSES}
     * @return a clause that completes "a death where ..."
     * @throws IllegalArgumentException if the cause is none of {@link #CAUSES}
     */
    static String meaningOf(String cause) {
        String meaning = MEANINGS.get(cause);
        if (meaning == null) {
            throw new IllegalArgumentException("No such cause of death: " + cause);
        }
        return meaning;
    }

    /**
     * Lists the causes with the number each is written as and with what it means.
     *
     * @return one sentence per cause, such as "0 (energy): the organism's energy was used up."
     */
    static String numberedCauses() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < CAUSES.size(); i++) {
            if (i > 0) {
                text.append(' ');
            }
            text.append(i).append(" (").append(CAUSES.get(i)).append("): ")
                .append(meaningOf(CAUSES.get(i))).append('.');
        }
        return text.toString();
    }

    /**
     * Finds the cause one dead organism died of.
     *
     * @param org the dead organism, with the energy and the entropy it died with
     * @return the index of the cause within {@link #CAUSES}
     * @throws IllegalStateException if the organism's state fits neither cause, or fits no death
     *         by energy while no run configuration states the entropy limit
     */
    int of(OrganismState org) {
        if (org.getEnergy() <= 0) {
            return ENERGY;
        }
        if (maxEntropy == null) {
            throw new IllegalStateException("Organism " + org.getOrganismId() + " died with energy "
                + org.getEnergy() + ", and no run configuration states the entropy limit its death "
                + "would have to be read against.");
        }
        if (org.getEntropyRegister() > maxEntropy) {
            return ENTROPY;
        }
        throw new IllegalStateException("Organism " + org.getOrganismId() + " died with energy "
            + org.getEnergy() + " and entropy " + org.getEntropyRegister() + ", which is neither a "
            + "death by energy nor one by entropy above " + maxEntropy + ".");
    }
}
