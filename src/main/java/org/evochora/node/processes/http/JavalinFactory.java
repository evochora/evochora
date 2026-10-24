package org.evochora.node.processes.http;

import java.util.function.Consumer;

import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.json.JavalinJackson3;

/**
 * Creates the Javalin applications of the node, so that every one of them writes JSON the same way.
 * <p>
 * Javalin writes JSON through Jackson 2 unless told otherwise, while the response types of the
 * node carry Jackson 3 annotations - among them the one that sends 64-bit identifiers as strings,
 * because JavaScript cannot hold them as numbers. A server, or a test, that created its Javalin
 * directly would ignore those annotations; created here, it writes what the node writes.
 */
public final class JavalinFactory {

    private JavalinFactory() {
    }

    /**
     * Creates a Javalin application with the node's JSON mapping and the given configuration.
     *
     * @param config The rest of the configuration, applied after the JSON mapping.
     * @return The application, not yet started.
     */
    public static Javalin create(final Consumer<JavalinConfig> config) {
        return Javalin.create(javalin -> {
            javalin.jsonMapper(new JavalinJackson3());
            config.accept(javalin);
        });
    }
}
