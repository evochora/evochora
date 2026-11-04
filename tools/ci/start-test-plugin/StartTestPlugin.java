package starttest;

import org.evochora.runtime.Simulation;
import org.evochora.runtime.spi.IRandomProvider;
import org.evochora.runtime.spi.ITickPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

/**
 * A tick plugin built the way a user builds one, against the jars of a distribution, and run from
 * its plugins directory by tools/ci/start_test.py. It logs one line on its first tick, which the
 * start test looks for, and does nothing else.
 */
public final class StartTestPlugin implements ITickPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger(StartTestPlugin.class);

    private boolean ran;

    /**
     * Creates the plugin as the simulation engine creates every plugin.
     *
     * @param random the simulation's random provider, not used
     * @param options the plugin's options from the configuration, not used
     */
    public StartTestPlugin(IRandomProvider random, Config options) {
    }

    @Override
    public void execute(Simulation simulation) {
        if (!ran) {
            ran = true;
            LOGGER.info("Start test plugin ran at tick {}", simulation.getCurrentTick());
        }
    }

    @Override
    public byte[] saveState() {
        return new byte[0];
    }

    @Override
    public void loadState(byte[] state) {
    }
}
