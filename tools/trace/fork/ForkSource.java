package org.evochora.tools.trace;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.evochora.cli.config.ConfigLoader;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigRenderOptions;
import com.typesafe.config.ConfigUtil;

/**
 * Resolves the storage resource a recorded run was written to, so that a trace can read the run
 * without taking over the rest of that configuration.
 * <p>
 * The configuration is loaded the way the node loads it, with {@code reference.conf} below it,
 * and the named resource under {@code pipeline.resources} is written as a resolved JSON object:
 * substitutions such as {@code ${pipeline.dataBaseDir}} are replaced by their values, so the
 * definition keeps pointing at the run's storage when it is placed into a configuration whose
 * {@code pipeline.dataBaseDir} points elsewhere. The class name and every option are taken over
 * unchanged, whatever kind of storage the resource is.
 * <p>
 * Usage: {@code ForkSource <config file> <resource name> <output file>}. Exits with 1 and a message
 * on standard error when the file or the resource does not exist.
 */
public final class ForkSource {

    private ForkSource() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: ForkSource <config file> <resource name> <output file>");
            System.exit(2);
        }
        File configFile = new File(args[0]);
        String resourceName = args[1];
        Path output = Path.of(args[2]);

        Config config;
        try {
            config = ConfigLoader.resolve(configFile, (level, message) -> { });
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }
        String path = ConfigUtil.joinPath("pipeline", "resources", resourceName);
        if (!config.hasPath(path) || !config.hasPath(ConfigUtil.joinPath("pipeline", "resources", resourceName, "className"))) {
            System.err.println("no storage resource '" + resourceName + "' under pipeline.resources in " + configFile);
            System.exit(1);
        }
        String json = config.getConfig(path).root().render(ConfigRenderOptions.concise());
        Files.writeString(output, json, StandardCharsets.UTF_8);
    }
}
