package org.evochora.compiler.internal;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.evochora.compiler.api.MachineInstructionInfo;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceFile;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.compiler.api.TokenKind;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the shape of an artifact as the {@code compile} command writes it: the string
 * form of a position where it is a map key, and the structure of the sources and of the
 * instructions per line, read back from the JSON of a small hand-built artifact.
 */
@Tag("unit")
class LinearizedArtifactShapeTest {

    private static final EnvironmentProperties ENV = new EnvironmentProperties(new int[]{10, 10}, true);

    @Test
    void aPositionIsWrittenWithItsPlacementButWithoutItsInstance() {
        assertThat(new SerializableSourceInfo("lib.evo", 2, 5, "LIB", 3)).hasToString("LIB@lib.evo:2:5");
        assertThat(new SerializableSourceInfo("main.evo", 2, 5, "", 0)).hasToString("main.evo:2:5");
        assertThat(new SerializableSourceInfo(null, 1, 1, "", 0)).hasToString("<unknown>:1:1");
    }

    @Test
    void theJsonCarriesTheSourcesWithTheirRecordsAndTheInstructionsByPlacementFileInstanceAndLine() {
        SourceFile lib = new SourceFile("LIB", "lib.evo", "/p/lib.evo", 0, new SourceInfo("/p/main.evo", 4, 1, "", 0),
                List.of("A", "B", "C"),
                List.of(new SourceFile.LeftOut(1, 1, 2, 3)), List.of(new SourceFile.Note(0, 1, 4, "text")));
        ProgramArtifact artifact = new ProgramArtifact("id", List.of(lib), Map.of(1, 0), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(new SourceInfo("/p/lib.evo", 2, 5, "LIB", 0), new TokenInfo("A", TokenKind.CONSTANT, "global")),
                Map.of(),
                Map.of("LIB", Map.of("/p/lib.evo", Map.of(0, Map.of(2, List.of(new MachineInstructionInfo(0, "NOP", "", false)))))),
                Map.of(), Map.of());

        JsonObject json = JsonParser.parseString(new Gson().toJson(artifact.toLinearized(ENV))).getAsJsonObject();

        JsonArray sources = json.getAsJsonArray("sources");
        assertThat(sources).hasSize(1);
        JsonObject source = sources.get(0).getAsJsonObject();
        assertThat(source.get("placement").getAsString()).isEqualTo("LIB");
        assertThat(source.get("path").getAsString()).isEqualTo("lib.evo");
        assertThat(source.get("resolvedPath").getAsString()).isEqualTo("/p/lib.evo");
        assertThat(source.get("instance").getAsInt()).isZero();
        JsonObject includedAt = source.getAsJsonObject("includedAt");
        assertThat(includedAt.get("fileName").getAsString()).isEqualTo("/p/main.evo");
        assertThat(includedAt.get("lineNumber").getAsInt()).isEqualTo(4);
        assertThat(source.getAsJsonArray("lines")).hasSize(3);
        JsonObject region = source.getAsJsonArray("leftOut").get(0).getAsJsonObject();
        assertThat(region.get("expansion").getAsInt()).isEqualTo(1);
        assertThat(region.get("directiveLine").getAsInt()).isEqualTo(1);
        assertThat(region.get("from").getAsInt()).isEqualTo(2);
        assertThat(region.get("to").getAsInt()).isEqualTo(3);
        JsonObject note = source.getAsJsonArray("notes").get(0).getAsJsonObject();
        assertThat(note.get("column").getAsInt()).isEqualTo(4);
        assertThat(note.get("text").getAsString()).isEqualTo("text");

        assertThat(json.getAsJsonObject("expansionHomes").get("1").getAsInt()).isZero();

        assertThat(json.getAsJsonObject("tokenMap").keySet()).containsExactly("LIB@/p/lib.evo:2:5");

        JsonArray line = json.getAsJsonObject("sourceLineToInstructions").getAsJsonObject("LIB")
                .getAsJsonObject("/p/lib.evo").getAsJsonObject("0").getAsJsonArray("2");
        assertThat(line).hasSize(1);
        assertThat(line.get(0).getAsJsonObject().get("opcode").getAsString()).isEqualTo("NOP");
    }
}
