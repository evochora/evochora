package org.evochora.node.processes.http.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.evochora.BuildInfo;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.node.processes.http.JavalinFactory;
import org.evochora.node.spi.ServiceRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.typesafe.config.ConfigFactory;

import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;

/**
 * Tests that the served OpenAPI specification names the API and the build serving it, which the
 * generated file leaves empty.
 */
@Tag("unit")
class OpenApiControllerTest {

    /**
     * Served without the other controllers, the paths of the specification find no base path, and
     * the controller warns about each; that is the setup of this test, not what it checks.
     */
    @Test
    @AllowLog(level = LogLevel.WARN, messagePattern = "OpenAPI: .*")
    void theSpecificationCarriesTitleVersionAndRevision() {
        final OpenApiController controller = new OpenApiController(new ServiceRegistry(), ConfigFactory.empty());
        final Javalin app = JavalinFactory.create(config -> controller.registerRoutes(config.routes, "/api-docs"));

        JavalinTest.test(app, (server, client) -> {
            final var response = client.get("/api-docs/openapi.json");
            assertThat(response.code()).isEqualTo(200);
            final JsonObject info = JsonParser.parseString(response.body().string()).getAsJsonObject()
                .getAsJsonObject("info");
            assertThat(info.get("title").getAsString()).isEqualTo("Evochora API");
            assertThat(info.get("version").getAsString()).isEqualTo(BuildInfo.version()).isNotBlank();
            assertThat(info.get("description").getAsString()).contains(BuildInfo.revision());
        });
    }
}
