package org.evochora.compiler.backend.emit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Registry for emission contributors, invoked in order during the emission pass.
 */
public final class EmissionContributorRegistry {

    private final List<IEmissionContributor> contributors = new ArrayList<>();

    /**
     * Registers a new emission contributor.
     *
     * @param contributor The contributor to register.
     */
    public void register(IEmissionContributor contributor) {
        contributors.add(contributor);
    }

    /**
     * Returns the contributors in registration order; the emitter invokes them in that
     * order for every IR item.
     *
     * @return The list of registered contributors.
     */
    public List<IEmissionContributor> contributors() {
        return Collections.unmodifiableList(contributors);
    }
}
