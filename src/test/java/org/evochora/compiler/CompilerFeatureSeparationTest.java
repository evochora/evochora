package org.evochora.compiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.InternalCompilerException;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Proves that the core and the features are kept apart, on the reference program.
 * <p>
 * The program is divided into blocks, each opened by a comment {@code #> needs:} with the
 * features the block needs and closed by a comment {@code #<}; what stands outside every block
 * needs no feature. The program compiles with every feature. Without one feature, the program as
 * it is fails with a message, never with an internal error, and every place the message names
 * lies in a block that needs that feature; the program without those blocks compiles. Without
 * any feature, the program fails the same way, and what stands outside every block compiles.
 * <p>
 * A failure names a hidden dependency: a block that needs a feature it does not name, or a part
 * of the core or of another feature that cannot do without the feature left out.
 */
@Tag("integration")
class CompilerFeatureSeparationTest {

    private static final Pattern OPENER = Pattern.compile("^\\s*#>\\s*needs:\\s*(.+?)\\s*$");
    private static final Pattern CLOSER = Pattern.compile("^\\s*#<\\s*$");

    /** A place a message names: a source file and a line. */
    private static final Pattern PLACE = Pattern.compile("([^\\s:\\[]+\\.evo):(\\d+)");

    /** A block of a file: its first and last line, both markers included, and the features it needs. */
    private record Block(Path file, int firstLine, int lastLine, Set<String> needs) {
        boolean holds(Path where, int line) {
            return file.equals(where) && line >= firstLine && line <= lastLine;
        }
    }

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void theProgramCompilesWithEveryFeature() throws Exception {
        Path root = ReferenceProgram.directory();

        assertThatCode(() -> compile(StandardFeatures.all(), root)).doesNotThrowAnyException();
    }

    @Test
    void everyBlockNamesStandardFeaturesAndEveryFeatureHasABlock() throws Exception {
        List<Block> blocks = blocks(ReferenceProgram.directory());
        Set<String> names = featureNames();

        Set<String> needed = new LinkedHashSet<>();
        blocks.forEach(block -> needed.addAll(block.needs()));
        assertThat(names).containsAll(needed);
        assertThat(needed).containsExactlyInAnyOrderElementsOf(names);
    }

    @TestFactory
    Stream<DynamicTest> withoutEachFeatureTheProgramFailsAtItsBlocksAndCompilesWithoutThem(@TempDir Path temp)
            throws Exception {
        Path root = ReferenceProgram.directory();
        List<Block> blocks = blocks(root);
        return featureNames().stream().map(name -> DynamicTest.dynamicTest("without " + name, () -> {
            List<ICompilerFeature> features = new ArrayList<>(StandardFeatures.all());
            features.removeIf(feature -> feature.name().equals(name));

            assertFailsInBlocks(features, root, blocks.stream().filter(block -> block.needs().contains(name)).toList());

            Path copy = Files.createDirectory(temp.resolve(name));
            copyWithout(root, copy, blocks, block -> block.needs().contains(name));
            assertThatCode(() -> compile(features, copy)).doesNotThrowAnyException();
        }));
    }

    @Test
    void withoutAnyFeatureOnlyWhatStandsOutsideTheBlocksCompiles(@TempDir Path temp) throws Exception {
        Path root = ReferenceProgram.directory();
        List<Block> blocks = blocks(root);

        assertFailsInBlocks(List.of(), root, blocks);

        copyWithout(root, temp, blocks, block -> true);
        assertThatCode(() -> compile(List.of(), temp)).doesNotThrowAnyException();
    }

    /**
     * Asserts that the program as it is fails with a message, and that every place the message
     * names lies in one of the blocks given.
     */
    private static void assertFailsInBlocks(List<ICompilerFeature> features, Path root, List<Block> allowed) {
        Throwable failure = catchThrowable(() -> compile(features, root));

        assertThat(failure).isInstanceOf(CompilationException.class).isNotInstanceOf(InternalCompilerException.class);
        List<String> outside = new ArrayList<>();
        int places = 0;
        Matcher matcher = PLACE.matcher(failure.getMessage());
        while (matcher.find()) {
            places++;
            Path where = root.resolve(relativeTo(root, matcher.group(1))).normalize();
            int line = Integer.parseInt(matcher.group(2));
            if (allowed.stream().noneMatch(block -> block.holds(where, line))) {
                outside.add(matcher.group(0));
            }
        }
        assertThat(places).as("places named by: %s", failure.getMessage()).isPositive();
        assertThat(outside).as("places outside the blocks of the feature, in: %s", failure.getMessage()).isEmpty();
    }

    private static String relativeTo(Path root, String named) {
        String rootText = root.toString().replace('\\', '/') + "/";
        String path = named.replace('\\', '/');
        return path.startsWith(rootText) ? path.substring(rootText.length()) : path;
    }

    private static void compile(List<ICompilerFeature> features, Path root) throws Exception {
        new Compiler(features).compile(ReferenceProgram.MAIN_FILE, ReferenceProgram.ENV, ReferenceProgram.options(root));
    }

    private static Set<String> featureNames() {
        Set<String> names = new LinkedHashSet<>();
        StandardFeatures.all().forEach(feature -> names.add(feature.name()));
        return names;
    }

    private static List<Path> sourceFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(file -> file.toString().endsWith(".evo")).sorted().toList();
        }
    }

    /**
     * Reads the blocks of every source file of the program. A block that is not closed, a closer
     * without an opener and a block inside another are defects of the program.
     */
    private static List<Block> blocks(Path root) throws IOException {
        List<Block> blocks = new ArrayList<>();
        for (Path file : sourceFiles(root)) {
            List<String> lines = Files.readAllLines(file);
            int openedAt = 0;
            Set<String> needs = null;
            for (int i = 0; i < lines.size(); i++) {
                int line = i + 1;
                Matcher opener = OPENER.matcher(lines.get(i));
                if (opener.matches()) {
                    if (needs != null) {
                        throw new IllegalStateException(file + ":" + line + ": a block opens inside the block opened at line " + openedAt);
                    }
                    openedAt = line;
                    needs = new LinkedHashSet<>();
                    for (String name : opener.group(1).split(",")) {
                        needs.add(name.trim());
                    }
                } else if (CLOSER.matcher(lines.get(i)).matches()) {
                    if (needs == null) {
                        throw new IllegalStateException(file + ":" + line + ": a block closes that was not opened");
                    }
                    blocks.add(new Block(file.normalize(), openedAt, line, Set.copyOf(needs)));
                    needs = null;
                }
            }
            if (needs != null) {
                throw new IllegalStateException(file + ":" + openedAt + ": the block is not closed");
            }
        }
        return blocks;
    }

    /**
     * Copies the program into a directory, every line of a block the predicate takes left empty,
     * so that every other line keeps its number.
     */
    private static void copyWithout(Path root, Path target, List<Block> blocks,
                                    java.util.function.Predicate<Block> left) throws IOException {
        for (Path file : sourceFiles(root)) {
            List<String> lines = new ArrayList<>(Files.readAllLines(file));
            for (Block block : blocks) {
                if (block.file().equals(file.normalize()) && left.test(block)) {
                    for (int line = block.firstLine(); line <= block.lastLine(); line++) {
                        lines.set(line - 1, "");
                    }
                }
            }
            Path copy = target.resolve(root.relativize(file));
            Files.createDirectories(copy.getParent());
            Files.write(copy, lines);
        }
    }
}
