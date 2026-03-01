package org.evochora.compiler;

/**
 * A self-contained compiler feature that registers its components into the phase pipeline.
 *
 * <p>Each feature implements this interface and registers all its handlers (parser, semantics,
 * IR generation, linking, etc.) via the {@link IFeatureRegistrationContext}. Features are
 * stateless — all compilation data flows through phase-specific contexts, not through features.</p>
 */
public interface ICompilerFeature {

	/**
	 * Returns the name of this feature, used for diagnostics and logging.
	 *
	 * @return The feature name (e.g., "instruction", "proc", "label").
	 */
	String name();

	/**
	 * Registers all components of this feature into the phase pipeline.
	 *
	 * @param ctx The registration context that accepts handler registrations for each phase.
	 */
	void register(IFeatureRegistrationContext ctx);
}
