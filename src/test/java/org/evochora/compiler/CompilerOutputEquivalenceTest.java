package org.evochora.compiler;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compiles the reference program under {@code src/test/resources/org/evochora/compiler/reference/}
 * and compares the artifact with the one checked in next to it.
 * <p>
 * The reference program uses every feature of the compiler once, so a change to any phase that
 * alters the generated code, the placed molecules or the debug information changes the artifact
 * and fails this test. A compiler change that is meant to leave the output alone is thereby
 * verified rather than assumed, and a change that is meant to alter the output has to regenerate
 * the checked-in artifact, which puts the difference in front of the reviewer.
 * <p>
 * The artifact is serialized exactly as the {@code compile} command of the CLI writes it, so the
 * checked-in file is what a user of that command would see. The comparison is made on the parsed
 * JSON, not on the text, because the artifact holds maps whose iteration order is not part of
 * the output. The compiled artifact is always written to {@code build/reference-artifacts/}: on
 * a failure, that file is the one to diff against the checked-in artifact, and, if the
 * difference is intended, the one to copy over it. Leaving that file behind is a deliberate
 * exception to the rule that a test cleans up after itself: the file is the test's finding.
 */
@Tag("integration")
class CompilerOutputEquivalenceTest {

    /** Environment the reference program is compiled for; the wildcard placement needs one. */
    private static final EnvironmentProperties ENV = new EnvironmentProperties(new int[]{100, 100}, true);

    /** Classpath directory holding the reference program and its checked-in artifact. */
    private static final String REFERENCE_DIR = "/org/evochora/compiler/reference/";

    /** The name of the main file of the reference program, relative to the reference directory. */
    private static final String MAIN_FILE = "main.evo";

    /** Where the artifact compiled by this run is written for diffing and regeneration. */
    private static final Path ACTUAL_DIR = Path.of("build", "reference-artifacts");

    /**
     * Stands in for the directory the reference program is compiled from. The artifact records
     * file paths, and the directory differs between machines, so it is replaced before the
     * artifact is compared or written.
     */
    private static final String ROOT_PLACEHOLDER = "REFERENCE_ROOT";

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void referenceProgramCompilesToTheCheckedInArtifact() throws Exception {
        Path referenceRoot = Path.of(getClass().getResource(REFERENCE_DIR + MAIN_FILE).toURI()).getParent();
        CompilerOptions options = new CompilerOptions(List.of(new SourceRoot(referenceRoot.toString(), null)));

        ProgramArtifact artifact = new Compiler().compile(MAIN_FILE, ENV, options);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String actualJson = withoutReferenceRoot(gson.toJson(artifact.toLinearized(ENV)), referenceRoot);
        Files.createDirectories(ACTUAL_DIR);
        Path actualFile = ACTUAL_DIR.resolve("main.json");
        Files.writeString(actualFile, actualJson);

        URL expectedUrl = getClass().getResource(REFERENCE_DIR + "expected/main.json");
        assertThat(expectedUrl)
                .as("checked-in artifact of the reference program; the compiled one is at %s", actualFile)
                .isNotNull();
        JsonElement expected = JsonParser.parseString(Files.readString(Path.of(expectedUrl.toURI())));
        JsonElement actual = JsonParser.parseString(actualJson);

        assertThat(actual)
                .as("artifact of the reference program differs from the checked-in one; diff %s against %s",
                        actualFile, Path.of(expectedUrl.toURI()))
                .isEqualTo(expected);
    }

    /**
     * Replaces the reference directory in the serialized artifact by a placeholder. The compiler
     * records file paths with forward slashes, but the directory is also replaced in the form the
     * platform writes it, so the artifact is the same on every machine.
     */
    private static String withoutReferenceRoot(String json, Path referenceRoot) {
        String platformForm = referenceRoot.toString();
        String compilerForm = platformForm.replace('\\', '/');
        return json.replace(compilerForm, ROOT_PLACEHOLDER).replace(platformForm, ROOT_PLACEHOLDER);
    }
}
