package org.evochora.compiler.backend.emit;

import java.util.ArrayList;
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
     * @return The list of registered contributors.
     */
    public List<IEmissionContributor> contributors() {
        return contributors;
    }

    /**
     * Initializes a new registry with the default contributors.
     *
     * @return A new registry with default contributors.
     */
    public static EmissionContributorRegistry initializeWithDefaults() {
        EmissionContributorRegistry registry = new EmissionContributorRegistry();
        registry.register(new ProcedureEmissionContributor());
        registry.register(new RegisterAliasEmissionContributor());
        return registry;
    }
}
