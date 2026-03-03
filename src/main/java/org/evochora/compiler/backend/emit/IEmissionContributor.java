package org.evochora.compiler.backend.emit;

import org.evochora.compiler.model.ir.IrItem;

/**
 * Contributor for Phase 11 (Emission).
 *
 * <p>Each contributor processes IR items during the emission pass and contributes
 * metadata to the {@link EmissionContext}. The Emitter invokes all registered
 * contributors for each IR item before performing core emission logic.</p>
 */
public interface IEmissionContributor {

    /**
     * Processes a single IR item during emission.
     *
     * @param item    The IR item being emitted.
     * @param context The emission context for contributing metadata.
     */
    void onItem(IrItem item, EmissionContext context);
}
