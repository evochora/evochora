package org.evochora.node.processes.http.api.visualizer;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.evochora.datapipeline.TestMetadataHelper;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.database.IDatabaseReaderProvider;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareMetadataWriter;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareOrganismDataWriter;
import org.evochora.datapipeline.resources.database.H2Database;
import org.evochora.junit.extensions.logging.ExpectLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.node.spi.ServiceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.typesafe.config.ConfigFactory;

import io.javalin.Javalin;
import io.restassured.response.Response;

/**
 * Tests for the {@code root} parameter of the organisms-of-a-tick route of
 * {@link OrganismController}, against a run indexed into an in-memory H2 database.
 * <p>
 * The run: founder 1 with children 2 and 3, 4 a child of 2, 5 a child of 4. All five are
 * recorded at tick 10; at tick 20 only 3 and 5 are alive.
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class OrganismControllerDescentTest {

    private static final String BASE = "/visualizer/api/organisms";

    private H2Database database;
    private OrganismController controller;
    private Javalin app;
    private int port;
    private String runId;

    @BeforeEach
    void setUp() throws Exception {
        database = new H2Database("test-db", ConfigFactory.parseString(
            "jdbcUrl = \"jdbc:h2:mem:test-descent-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL\"\n"
            + "username = \"sa\"\npassword = \"\"\nmaxPoolSize = 5\n"));
        runId = "run-descent-" + UUID.randomUUID();

        final var metaWriter = (IResourceSchemaAwareMetadataWriter) database.getWrappedResource(
            new ResourceContext("test", "metadata-port", "db-meta-write", "test-db", Map.of()));
        metaWriter.setSimulationRun(runId);
        metaWriter.insertMetadata(SimulationMetadata.newBuilder()
            .setSimulationRunId(runId)
            .setResolvedConfigJson(TestMetadataHelper.createResolvedConfigJson(100, 100))
            .build());
        ((AutoCloseable) metaWriter).close();

        final var orgWriter = (IResourceSchemaAwareOrganismDataWriter) database.getWrappedResource(
            new ResourceContext("test", "organism-port", "db-organism-write", "test-db", Map.of()));
        orgWriter.setSimulationRun(runId);
        orgWriter.createOrganismTables();
        orgWriter.writeOrganismTick(TickData.newBuilder()
            .setTickNumber(10).setSimulationRunId(runId).setTotalOrganismsCreated(5)
            .addOrganisms(organism(1, null)).addOrganisms(organism(2, 1)).addOrganisms(organism(3, 1))
            .addOrganisms(organism(4, 2)).addOrganisms(organism(5, 4))
            .build(), Map.of());
        orgWriter.writeOrganismTick(TickData.newBuilder()
            .setTickNumber(20).setSimulationRunId(runId).setTotalOrganismsCreated(5)
            .addOrganisms(organism(3, 1)).addOrganisms(organism(5, 4))
            .build(), Map.of());
        orgWriter.commitOrganismWrites();
        ((AutoCloseable) orgWriter).close();

        serve(ConfigFactory.empty());
    }

    /** Starts a server with a controller configured as given, replacing a running one. */
    private void serve(final com.typesafe.config.Config options) {
        if (app != null) {
            app.stop();
            controller.close();
        }
        final ServiceRegistry registry = new ServiceRegistry();
        registry.register(IDatabaseReaderProvider.class, database);
        controller = new OrganismController(registry, options);
        app = Javalin.create().start(0);
        port = app.port();
        controller.registerRoutes(app, BASE);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (app != null) {
            app.stop();
        }
        if (controller != null) {
            controller.close();
        }
        if (database != null) {
            database.close();
        }
    }

    private static OrganismState organism(final int id, final Integer parent) {
        final OrganismState.Builder builder = OrganismState.newBuilder()
            .setOrganismId(id)
            .setBirthTick(id)
            .setProgramId("prog")
            .setInitialPosition(Vector.newBuilder().addComponents(id).addComponents(2 * id).build())
            .setEnergy(42)
            .setIp(Vector.newBuilder().addComponents(id).addComponents(0).build())
            .setDv(Vector.newBuilder().addComponents(0).addComponents(1).build());
        if (parent != null) {
            builder.setParentId(parent);
        }
        return builder.build();
    }

    private Response tick(final long tick, final String root) {
        return given().port(port).queryParam("runId", runId).queryParam("root", root).get(BASE + "/" + tick);
    }

    /** Asks until the index is ready, which the first request sets going. */
    private Response awaitReady(final long tick, final String root) {
        final Response[] last = new Response[1];
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            last[0] = tick(tick, root);
            return "ready".equals(last[0].path("descent.state"));
        });
        return last[0];
    }

    @Test
    void withoutARootTheAnswerCarriesNoDescent() {
        given().port(port).queryParam("runId", runId).get(BASE + "/20")
        .then()
            .statusCode(200)
            .body("organisms.organismId", contains(3, 5))
            .body("totalOrganismCount", equalTo(5))
            .body("$", not(hasKey("descent")))
            .body("$", not(hasKey("genomeAncestors")));
    }

    @Test
    void autoIsLoadingAtFirstAndThenResolvesToTheCommonAncestorOfTheLiving() {
        tick(20, "auto").then()
            .statusCode(200)
            .body("descent.state", equalTo("loading"))
            .body("descent.organismsInRun", equalTo(0))
            .body("descent.root", nullValue())
            .body("descent.lines", empty());

        awaitReady(20, "auto").then()
            .statusCode(200)
            .body("descent.progress", equalTo(1.0f))
            .body("descent.organismsInRun", equalTo(5))
            .body("descent.root.id", equalTo(1))
            .body("descent.root.birthTick", equalTo(1))
            .body("descent.root.position", contains(1, 2))
            .body("descent.root.descendants", equalTo(4))
            .body("descent.lines.id", contains(2, 3))
            .body("descent.lines.descendants", contains(3, 1))
            .body("descent.lines.colour", contains(0, 1))
            .body("descent.lines.living", contains(1, 1))
            .body("descent.lines[0].landing.root", equalTo(5))
            .body("descent.lines[0].landing.skipped", equalTo(2))
            .body("descent.lines[1]", not(hasKey("landing")))
            .body("descent.up.oneStep", equalTo(0))
            .body("descent.lineOf.'3'", equalTo(3))
            .body("descent.lineOf.'5'", equalTo(2));
    }

    @Test
    void allHasTheFounderAsItsOnlyLineAndNoWayUp() {
        awaitReady(20, "all").then()
            .statusCode(200)
            .body("descent.root.id", equalTo(0))
            .body("descent.root", not(hasKey("birthTick")))
            .body("descent.lines.id", contains(1))
            .body("descent.up", nullValue())
            .body("descent.lineOf.'5'", equalTo(1));
    }

    @Test
    void theETagNamesTheRootAndTheStateAndVersionOfTheIndex() {
        serve(ConfigFactory.parseString("http-cache.organisms { enabled = true, maxAge = 60, useETag = true }"));

        // Root as requested, state, version, cursor and newest total of the index, root's death tick
        tick(20, "auto").then().statusCode(200)
            .header("ETag", equalTo("\"" + runId + "_20_auto_loading_0_0_0_-\""));
        final String ready = awaitReady(20, "all").header("ETag");
        assertThat(ready).startsWith("\"" + runId + "_20_all_ready_").endsWith("_5_5_-\"");
        tick(20, "1").then().statusCode(200).header("ETag", endsWith("_5_5_-1\""));
        tick(20, "auto").then().statusCode(200).header("ETag", endsWith("_5_5_-1\""));
        given().port(port).queryParam("runId", runId).get(BASE + "/20")
            .then().statusCode(200).header("ETag", equalTo("\"" + runId + "_20\""));
    }

    @Test
    void aRootThatIsNotIndexedIsNotFound() {
        tick(20, "99").then().statusCode(404);
    }

    @Test
    @ExpectLog(level = LogLevel.WARN, messagePattern = "Invalid request parameters for .*", occurrences = 3)
    void aMalformedRootIsABadRequestAndNeverAll() {
        tick(20, "everything").then().statusCode(400);
        tick(20, "0").then().statusCode(400);
        tick(20, "").then().statusCode(400);
    }
}
