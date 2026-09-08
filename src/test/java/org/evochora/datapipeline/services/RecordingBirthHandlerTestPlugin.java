package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.MutationRecord;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.spi.IRandomProvider;
import org.evochora.runtime.spi.IBirthHandler;

/**
 * A birth handler that reports one mutation for every newborn, so that an engine test can watch
 * where the records go without a real mutation plugin having to fire.
 * <p>
 * The engine instantiates plugins by class name through the two-argument constructor every
 * simulation plugin has; both arguments are accepted and ignored.
 */
public final class RecordingBirthHandlerTestPlugin implements IBirthHandler {

    /** The kind the reported record carries. */
    public static final String KIND = "test-record";

    public RecordingBirthHandlerTestPlugin(IRandomProvider randomProvider, Config config) {
        // Stateless: neither argument is needed
    }

    @Override
    public void onBirth(Organism child, Environment environment) {
        child.recordBirthMutation(new MutationRecord(getClass().getName(), KIND,
                new int[]{0}, new int[]{1}, new int[]{2}, new long[0], child.getDv()));
    }

    @Override
    public byte[] saveState() {
        return new byte[0];
    }

    @Override
    public void loadState(byte[] state) {
        // Stateless
    }
}
