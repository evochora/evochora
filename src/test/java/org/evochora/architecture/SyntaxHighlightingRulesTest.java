package org.evochora.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Holds the editor extension's opcode list to the instruction set.
 * <p>
 * The list in {@code evochora.tmLanguage.json} names every instruction a second time, and nothing
 * else in the build reads it, so an instruction the registry holds but the list does not stays
 * unhighlighted until someone notices in an editor. This rule makes that duplication visible at
 * build time instead.
 * <p>
 * Only the instruction names are checked. Directives and register banks are named in the same file
 * and have no registry to compare against; they are read and corrected by hand.
 * <p>
 * The grammar is a file of the repository rather than a resource of this build, so the rule reads
 * it from the project directory — which is why it is an integration test and not a unit test. It
 * parses the file as JSON and takes the pattern from its grammar rule, so that reformatting the
 * file changes nothing here.
 */
@Tag("integration")
class SyntaxHighlightingRulesTest {

    private static final Path GRAMMAR =
            Path.of("extensions/vscode/src/extension/syntaxes/evochora.tmLanguage.json");

    /** The pattern the editor highlights instructions with, as the grammar declares it. */
    private static Pattern opcodePattern;

    /** The names that pattern lists, in the order-independent form this rule compares. */
    private static Set<String> highlightedNames;

    private static Set<String> registeredNames;

    @BeforeAll
    static void readGrammar() throws IOException {
        Instruction.init();
        registeredNames = new TreeSet<>(Instruction.getAllInstructions().values());

        JsonNode grammar = new ObjectMapper().readTree(Files.readString(GRAMMAR));
        JsonNode match = grammar.path("repository").path("opcodes").path("match");
        assertThat(match.isTextual())
                .as("grammar rule 'opcodes' in %s", GRAMMAR)
                .isTrue();

        opcodePattern = Pattern.compile(match.asText());

        Matcher alternation = Pattern.compile("\\(([A-Z0-9|]+)\\)").matcher(match.asText());
        assertThat(alternation.find())
                .as("list of instruction names in the 'opcodes' pattern")
                .isTrue();
        highlightedNames = new TreeSet<>(Set.of(alternation.group(1).split("\\|")));
    }

    @Test
    void everyInstructionIsHighlighted() {
        Set<String> missing = registeredNames.stream()
                .filter(name -> !opcodePattern.matcher(name).find())
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(missing)
                .as("instructions the editor extension does not highlight")
                .isEmpty();
    }

    @Test
    void nothingIsHighlightedThatIsNotAnInstruction() {
        Set<String> unknown = highlightedNames.stream()
                .filter(name -> !registeredNames.contains(name))
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(unknown)
                .as("names the editor extension highlights as instructions but the instruction set does not know")
                .isEmpty();
    }
}
