package org.evochora.compiler;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.FlagSetting;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.runtime.model.EnvironmentProperties;

/**
 * The reference program under {@code src/test/resources/org/evochora/compiler/reference/}, as the
 * tests that compile it share it: where it lies, the world it is compiled for and the
 * configuration it is compiled with.
 */
final class ReferenceProgram {

    /**
     * The world the program is compiled for: wide enough for the code that runs along row 0
     * when no block moves the cursor, and a column for the wildcard placement.
     */
    static final EnvironmentProperties ENV = new EnvironmentProperties(new int[]{1024, 128}, true);

    /** The name of the main file, relative to the program's directory. */
    static final String MAIN_FILE = "main.evo";

    /** The flags the configuration sets: one with a value, one without. */
    static final Map<String, FlagSetting> DEFINES =
            Map.of("ROWS", FlagSetting.set(4), "VERBOSE", FlagSetting.set());

    /** The values the configuration sets: a literal, and a computation with the program's names. */
    static final Map<String, String> VALUES = Map.of("GAP", "DATA:3", "SPAN", "(SIZE * 2)");

    private static final String RESOURCE_DIR = "/org/evochora/compiler/reference/";

    private ReferenceProgram() {
    }

    /**
     * Returns the directory the program lies in, on the class path.
     *
     * @return The directory holding {@link #MAIN_FILE}.
     * @throws URISyntaxException if the class path location is no valid URI.
     */
    static Path directory() throws URISyntaxException {
        return Path.of(ReferenceProgram.class.getResource(RESOURCE_DIR + MAIN_FILE).toURI()).getParent();
    }

    /**
     * Returns the options the program is compiled with from a directory: that directory as the
     * only source root, and the configured flags and values.
     *
     * @param root The directory holding the program, the original or a copy.
     * @return The options.
     */
    static CompilerOptions options(Path root) {
        return new CompilerOptions(List.of(new SourceRoot(root.toString(), null)), DEFINES, VALUES);
    }
}
