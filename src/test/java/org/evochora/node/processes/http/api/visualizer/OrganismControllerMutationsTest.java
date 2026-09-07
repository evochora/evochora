package org.evochora.node.processes.http.api.visualizer;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.evochora.datapipeline.TestMetadataHelper;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.StoredMutationEvent;
import org.evochora.datapipeline.api.contracts.StoredMutationEvents;
import org.evochora.datapipeline.api.resources.database.IDatabaseReader;
import org.evochora.datapipeline.api.resources.database.IDatabaseReaderProvider;
import org.evochora.datapipeline.api.resources.database.OrganismNotFoundException;
import org.evochora.datapipeline.api.resources.database.dto.LineageMutations;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.node.spi.ServiceRegistry;
import org.evochora.runtime.Config;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.worldgen.LabelRewritePlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.typesafe.config.ConfigFactory;

import io.javalin.Javalin;

/**
 * Tests for the lineage mutations route of {@link OrganismController}.
 * <p>
 * The reader is mocked so that the chain and the world are fixed by the test; what is exercised is
 * the route itself, the placement of a recorded cell on the displayed body and the shape of the
 * JSON the visualizer reads.
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class OrganismControllerMutationsTest {

    private static final String RUN_ID = "run-mutations";
    private static final int MASK_PARENT = 0x1;
    private static final int MASK_CHILD = 0x2;
    private static final int RECORDED_LABEL_VALUE = 0x1234;

    private Javalin app;
    private int port;
    private IDatabaseReader reader;

    @BeforeEach
    void setUp() throws Exception {
        reader = mock(IDatabaseReader.class);
        IDatabaseReaderProvider provider = mock(IDatabaseReaderProvider.class);
        when(provider.createReader(any())).thenReturn(reader);
        when(reader.getMetadata()).thenReturn(SimulationMetadata.newBuilder()
                .setResolvedConfigJson(TestMetadataHelper.createResolvedConfigJson(100, 100))
                .build());

        app = Javalin.create().start(0);
        port = app.port();

        ServiceRegistry registry = new ServiceRegistry();
        registry.register(IDatabaseReaderProvider.class, provider);
        OrganismController controller = new OrganismController(registry, ConfigFactory.empty());
        controller.registerRoutes(app, "/visualizer/api/organisms");
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void answersWithTheEventsOfTheWholeLineageOnTheDisplayedBody() throws Exception {
        StoredMutationEvents parentEvents = StoredMutationEvents.newBuilder()
                .setDimensions(2)
                .addEvents(StoredMutationEvent.newBuilder()
                        .setPluginClass("org.evochora.runtime.worldgen.GeneSubstitutionPlugin")
                        .setKind("substitution")
                        .addRelativeCoordinates(-5).addRelativeCoordinates(0)
                        .addOldValues(new Molecule(Config.TYPE_CODE, 3).toInt())
                        .addNewValues(new Molecule(Config.TYPE_LABELREF, RECORDED_LABEL_VALUE).toInt())
                        .addDv(0).addDv(1))
                .addEvents(labelRewrite(MASK_PARENT))
                .build();
        StoredMutationEvents childEvents = StoredMutationEvents.newBuilder()
                .setDimensions(2)
                .addEvents(labelRewrite(MASK_CHILD))
                .build();

        when(reader.readLineageMutations(7)).thenReturn(List.of(
                new LineageMutations(7, 2, 300L, 90L, new int[]{2, 3}, childEvents),
                new LineageMutations(5, 1, 200L, 40L, new int[]{60, 70}, parentEvents)));

        given()
            .port(port)
            .queryParam("runId", RUN_ID)
            // The tick segment is not read; a tick the organism has no state at answers the same
            .get("/visualizer/api/organisms/4711/7/mutations")
        .then()
            .statusCode(200)
            .body("organismId", equalTo(7))
            .body("events", hasSize(3))
            .body("events.originOrganismId", contains(5, 5, 7))
            .body("events.originGeneration", contains(1, 1, 2))
            .body("events.eventIndex", contains(0, 1, 0))
            .body("events[0].originGenomeHash", equalTo("200"))
            .body("events[0].originBirthTick", equalTo(40))
            .body("events[0].pluginClass", equalTo("org.evochora.runtime.worldgen.GeneSubstitutionPlugin"))
            .body("events[0].kind", equalTo("substitution"))
            .body("events[0].dv", contains(0, 1))
            .body("events[0].params", empty())
            // The displayed organism starts at (2, 3), so the offset of -5 wraps around the world
            .body("events[0].cells[0].coordinates", contains(97, 3))
            .body("events[0].cells[0].before.moleculeType", equalTo(Config.TYPE_CODE))
            .body("events[0].cells[0].before.moleculeValue", equalTo(3))
            .body("events[0].cells[0].after.moleculeType", equalTo(Config.TYPE_LABELREF))
            // Both births masked the label after the substitution wrote it
            .body("events[0].cells[0].after.moleculeValue",
                equalTo(RECORDED_LABEL_VALUE ^ MASK_PARENT ^ MASK_CHILD))
            .body("events[1].kind", equalTo(LabelRewritePlugin.MUTATION_KIND))
            .body("events[1].cells", empty())
            .body("events[2].originOrganismId", equalTo(7));
    }

    @Test
    void reportsAnOrganismThatIsNotIndexedAsNotFound() throws Exception {
        when(reader.readLineageMutations(anyInt()))
                .thenThrow(new OrganismNotFoundException("No organism metadata for id 9"));

        given()
            .port(port)
            .queryParam("runId", RUN_ID)
            .get("/visualizer/api/organisms/0/9/mutations")
        .then()
            .statusCode(404);
    }

    private static StoredMutationEvent labelRewrite(int mask) {
        return StoredMutationEvent.newBuilder()
                .setPluginClass(LabelRewritePlugin.class.getName())
                .setKind(LabelRewritePlugin.MUTATION_KIND)
                .addParams(mask)
                .build();
    }
}
